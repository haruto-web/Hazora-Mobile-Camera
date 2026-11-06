package com.harutoweb.hazora.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

class MjpegCameraServerTest {
    @Test
    fun defaultPortsDoNotRequirePrivilegedPortPermissions() {
        assertTrue(MjpegCameraServer.DEFAULT_STREAM_PORT >= 1024)
        assertTrue(MjpegCameraServer.DEFAULT_CAPTURE_PORT >= 1024)
    }

    @Test
    fun captureReturnsLatestJpegWithCorsHeaders() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x11, 0x22, 0xff.toByte(), 0xd9.toByte())
        MjpegCameraServer(
            bindAddress = InetAddress.getLoopbackAddress(),
            streamPort = 0,
            capturePort = 0
        ).use { server ->
            server.start()
            server.publishFrame(jpeg)

            val connection =
                URL("http://127.0.0.1:${server.listeningCapturePort}/capture").openConnection()
                    as HttpURLConnection
            try {
                assertEquals(200, connection.responseCode)
                assertEquals("*", connection.getHeaderField("Access-Control-Allow-Origin"))
                assertEquals("image/jpeg", connection.contentType)
                assertArrayEquals(jpeg, connection.inputStream.use { it.readBytes() })
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test
    fun captureReturnsUnavailableUntilFirstFrameArrives() {
        MjpegCameraServer(
            bindAddress = InetAddress.getLoopbackAddress(),
            streamPort = 0,
            capturePort = 0
        ).use { server ->
            server.start()
            val connection =
                URL("http://127.0.0.1:${server.listeningCapturePort}/capture").openConnection()
                    as HttpURLConnection
            try {
                assertEquals(503, connection.responseCode)
            } finally {
                connection.disconnect()
            }
        }
    }

    @Test
    fun streamReturnsMjpegFramesOnTheStreamPort() {
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x33, 0x44, 0xff.toByte(), 0xd9.toByte())
        MjpegCameraServer(
            bindAddress = InetAddress.getLoopbackAddress(),
            streamPort = 0,
            capturePort = 0
        ).use { server ->
            server.start()
            server.publishFrame(jpeg)

            val connection =
                URL("http://127.0.0.1:${server.listeningStreamPort}/stream").openConnection()
                    as HttpURLConnection
            try {
                assertEquals(200, connection.responseCode)
                assertEquals("multipart/x-mixed-replace; boundary=frame", connection.contentType)
                val frameBoundary = connection.inputStream.readNBytes(8)
                assertTrue(String(frameBoundary, Charsets.US_ASCII).startsWith("--frame"))
            } finally {
                connection.disconnect()
            }
        }
    }
}
