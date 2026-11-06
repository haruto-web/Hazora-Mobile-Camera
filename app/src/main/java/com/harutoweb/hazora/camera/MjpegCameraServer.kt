package com.harutoweb.hazora.camera

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class MjpegCameraServer(
    private val bindAddress: InetAddress,
    private val streamPort: Int = DEFAULT_STREAM_PORT,
    private val capturePort: Int = DEFAULT_CAPTURE_PORT,
    private val onError: (IOException) -> Unit = {}
) : Closeable {
    private data class Frame(val id: Long, val jpeg: ByteArray)

    private val frameId = AtomicLong()
    private val latestFrame = AtomicReference<Frame?>()
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val clientExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "hazora-camera-client").apply { isDaemon = true }
    }

    @Volatile
    private var running = false

    @Volatile
    private var serverSockets: List<ServerSocket> = emptyList()

    @Volatile
    private var streamServerSocket: ServerSocket? = null

    @Volatile
    private var captureServerSocket: ServerSocket? = null

    private var acceptThreads: List<Thread> = emptyList()

    val listeningStreamPort: Int
        get() = streamServerSocket?.localPort ?: streamPort

    val listeningCapturePort: Int
        get() = captureServerSocket?.localPort ?: capturePort

    @Synchronized
    @Throws(IOException::class)
    fun start() {
        check(!running) { "Camera server is already running" }
        val openedSockets = mutableListOf<ServerSocket>()
        try {
            openedSockets += createServerSocket(capturePort)
            openedSockets += createServerSocket(streamPort)
        } catch (error: IOException) {
            openedSockets.forEach { it.close() }
            throw error
        }
        captureServerSocket = openedSockets[0]
        streamServerSocket = openedSockets[1]
        serverSockets = openedSockets
        running = true
        acceptThreads = openedSockets.mapIndexed { index, socket ->
            Thread({ acceptClients(socket) }, "hazora-camera-server-$index").apply {
                isDaemon = true
                start()
            }
        }
    }

    @Throws(IOException::class)
    private fun createServerSocket(port: Int): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, port), 8)
        }

    fun publishFrame(jpeg: ByteArray) {
        latestFrame.set(Frame(frameId.incrementAndGet(), jpeg))
    }

    private fun acceptClients(listener: ServerSocket) {
        while (running) {
            try {
                val client = listener.accept()
                clients.add(client)
                clientExecutor.execute { handleClient(client) }
            } catch (error: IOException) {
                if (running) onError(error)
                break
            } catch (error: java.util.concurrent.RejectedExecutionException) {
                if (running) onError(IOException("Could not accept a camera client", error))
                break
            }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = HEADER_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readLine(input) ?: return
            val requestParts = requestLine.split(' ', limit = 3)
            if (requestParts.size < 2) {
                writeResponse(client, 400, "Bad Request", "text/plain", "Bad request")
                return
            }

            var headerBytes = requestLine.length
            while (true) {
                val line = readLine(input) ?: break
                headerBytes += line.length
                if (headerBytes > MAX_HEADER_BYTES) throw IOException("HTTP headers are too large")
                if (line.isEmpty()) break
            }

            val method = requestParts[0]
            val path = requestParts[1].substringBefore('?')
            if (method == "OPTIONS") {
                writeOptionsResponse(client)
                return
            }
            if (method != "GET") {
                writeResponse(client, 405, "Method Not Allowed", "text/plain", "GET required")
                return
            }

            when (path) {
                "/capture" -> serveCapture(client)
                "/stream" -> serveStream(client)
                "/health" -> {
                    val body = if (latestFrame.get() == null) {
                        """{"status":"waiting_for_frame"}"""
                    } else {
                        """{"status":"streaming"}"""
                    }
                    writeResponse(client, 200, "OK", "application/json", body)
                }
                "/" -> writeResponse(
                    client,
                    200,
                    "OK",
                    "text/plain; charset=utf-8",
                    "Hazora Mobile Camera is running. Use /stream and /capture."
                )
                else -> writeResponse(client, 404, "Not Found", "text/plain", "Not found")
            }
        } catch (_: IOException) {
            // Browsers commonly close a live stream when its page is closed.
        } finally {
            clients.remove(client)
            try {
                client.close()
            } catch (_: IOException) {
                // The socket may already have been closed by the streaming loop.
            }
        }
    }

    private fun serveCapture(client: Socket) {
        val frame = latestFrame.get()
        if (frame == null) {
            writeResponse(client, 503, "Service Unavailable", "text/plain", "Camera frame not ready")
            return
        }

        val output = BufferedOutputStream(client.getOutputStream())
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: image/jpeg\r\n")
            append("Content-Length: ${frame.jpeg.size}\r\n")
            append(corsHeaders())
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.write(frame.jpeg)
        output.flush()
    }

    private fun serveStream(client: Socket) {
        val output = BufferedOutputStream(client.getOutputStream())
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: multipart/x-mixed-replace; boundary=frame\r\n")
            append(corsHeaders())
            append("Cache-Control: no-store, no-cache, must-revalidate\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        output.flush()

        var lastSentFrameId = -1L
        while (running && !client.isClosed) {
            val frame = latestFrame.get()
            if (frame == null || frame.id == lastSentFrameId) {
                try {
                    Thread.sleep(STREAM_POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                continue
            }

            output.write("--frame\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.write("Content-Type: image/jpeg\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.write("Content-Length: ${frame.jpeg.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.write(frame.jpeg)
            output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            lastSentFrameId = frame.id
        }
    }

    private fun writeOptionsResponse(client: Socket) {
        val headers = buildString {
            append("HTTP/1.1 204 No Content\r\n")
            append(corsHeaders())
            append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }
        client.getOutputStream().apply {
            write(headers.toByteArray(StandardCharsets.US_ASCII))
            flush()
        }
    }

    private fun writeResponse(
        client: Socket,
        statusCode: Int,
        statusText: String,
        contentType: String,
        body: String
    ) {
        val bodyBytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bodyBytes.size}\r\n")
            append(corsHeaders())
            append("Connection: close\r\n\r\n")
        }
        client.getOutputStream().apply {
            write(headers.toByteArray(StandardCharsets.US_ASCII))
            write(bodyBytes)
            flush()
        }
    }

    private fun corsHeaders(): String =
        "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Content-Type\r\n"

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>(64)
        while (bytes.size < MAX_HEADER_LINE_BYTES) {
            val value = input.read()
            if (value == -1) return if (bytes.isEmpty()) null else String(
                bytes.toByteArray(),
                StandardCharsets.US_ASCII
            )
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.add(value.toByte())
        }
        if (bytes.size >= MAX_HEADER_LINE_BYTES) {
            throw IOException("HTTP header line is too long")
        }
        return String(bytes.toByteArray(), StandardCharsets.US_ASCII)
    }

    @Synchronized
    override fun close() {
        running = false
        serverSockets.forEach { socket ->
            try {
                socket.close()
            } catch (_: IOException) {
                // Closing the listener also wakes its accept thread.
            }
        }
        serverSockets = emptyList()
        captureServerSocket = null
        streamServerSocket = null

        clients.forEach { client ->
            try {
                client.close()
            } catch (_: IOException) {
                // The client may have disconnected already.
            }
        }
        clients.clear()
        clientExecutor.shutdownNow()
        acceptThreads.forEach(Thread::interrupt)
        acceptThreads = emptyList()
    }

    companion object {
        const val DEFAULT_STREAM_PORT = 81
        const val DEFAULT_CAPTURE_PORT = 80
        private const val HEADER_TIMEOUT_MS = 5_000
        private const val MAX_HEADER_LINE_BYTES = 8_192
        private const val MAX_HEADER_BYTES = 16_384
        private const val STREAM_POLL_INTERVAL_MS = 40L
    }
}
