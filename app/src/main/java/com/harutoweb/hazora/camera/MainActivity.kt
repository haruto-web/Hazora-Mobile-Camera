package com.harutoweb.hazora.camera

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusText: TextView
    private lateinit var addressText: TextView
    private lateinit var endpointText: TextView
    private lateinit var startButton: Button
    private lateinit var wifiButton: Button
    private lateinit var copyAddressButton: Button

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var cameraServer: MjpegCameraServer? = null
    private var isStreaming = false
    private var wifiAddress: String? = null
    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startStreaming()
            } else if (::statusText.isInitialized) {
                statusText.text = "Camera permission is required to stream video."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createScreen()
        refreshWifiAddress()
    }

    override fun onResume() {
        super.onResume()
        if (::addressText.isInitialized) refreshWifiAddress()
    }

    override fun onStop() {
        stopStreaming()
        super.onStop()
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }

    private fun createScreen() {
        val background = 0xfff2f6f3.toInt()
        val ink = 0xff162a20.toInt()
        val muted = 0xff586b60.toInt()
        val green = 0xff18794e.toInt()
        val padding = dp(20)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, dp(12))
            setBackgroundColor(background)
        }

        root.addView(TextView(this).apply {
            text = "Hazora Mobile Camera"
            textSize = 25f
            setTextColor(ink)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "Use this phone as a live camera for your Hazora dashboard."
            textSize = 15f
            setTextColor(muted)
            setPadding(0, dp(6), 0, dp(14))
        })

        statusText = TextView(this).apply {
            text = "Camera is off"
            textSize = 15f
            setTextColor(ink)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xffe3ede6.toInt())
        }
        root.addView(statusText)

        previewView = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(0xff101714.toInt())
        }
        root.addView(
            previewView,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = dp(12)
                bottomMargin = dp(12)
            }
        )

        root.addView(TextView(this).apply {
            text = "Wi-Fi address"
            textSize = 13f
            setTextColor(muted)
        })
        addressText = TextView(this).apply {
            text = "Checking Wi-Fi..."
            textSize = 20f
            setTextColor(ink)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, dp(4), 0, dp(4))
        }
        val addressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        addressRow.addView(addressText, LinearLayout.LayoutParams(0, dp(44), 1f))
        copyAddressButton = Button(this).apply {
            text = "Copy IP"
            isEnabled = false
            setOnClickListener {
                val address = wifiAddress ?: return@setOnClickListener
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Camera IP address", address))
                Toast.makeText(this@MainActivity, "Camera IP copied", Toast.LENGTH_SHORT).show()
            }
        }
        addressRow.addView(copyAddressButton)
        root.addView(addressRow)

        endpointText = TextView(this).apply {
            text = "Connect this phone and the dashboard device to the same Wi-Fi, then enter the IP shown here in a dashboard stream box."
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(2), 0, dp(8))
        }
        root.addView(endpointText)

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        startButton = Button(this).apply {
            text = "Start camera"
            setTextColor(0xffffffff.toInt())
            setBackgroundColor(green)
            setOnClickListener { onStartCameraClicked() }
        }
        controls.addView(
            startButton,
            LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginEnd = dp(8)
            }
        )

        wifiButton = Button(this).apply {
            text = "Wi-Fi settings"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }
        }
        controls.addView(
            wifiButton,
            LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginStart = dp(8)
            }
        )
        root.addView(controls)

        root.addView(TextView(this).apply {
            text = "Keep this screen open while streaming. Anyone on the same Wi-Fi with this IP can view the camera. Stop the camera when finished."
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(10), 0, 0)
        })

        setContentView(root)
    }

    private fun onStartCameraClicked() {
        if (isStreaming) {
            stopStreaming()
            return
        }

        refreshWifiAddress()
        if (wifiAddress == null) {
            statusText.text = "Connect to Wi-Fi before starting the camera."
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startStreaming()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startStreaming() {
        val address = wifiAddress
        if (address == null) {
            statusText.text = "Connect to Wi-Fi before starting the camera."
            return
        }

        try {
            cameraServer = MjpegCameraServer(
                bindAddress = Inet4Address.getByName(address),
                onError = { error ->
                    runOnUiThread {
                        if (isStreaming) {
                            statusText.text = "Camera server stopped: ${error.message}"
                            stopStreaming()
                        }
                    }
                }
            ).also { it.start() }
        } catch (error: Exception) {
            cameraServer?.close()
            cameraServer = null
            statusText.text = "Could not start camera server: ${error.message}"
            return
        }

        isStreaming = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        startButton.text = "Stop camera"
        wifiButton.isEnabled = false
        statusText.text = "Starting camera..."
        endpointText.text =
            "Website stream: http://$address:8081/stream\nDetection snapshots: http://$address:8080/capture"
        bindCamera()
    }

    private fun bindCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            if (!isStreaming) return@addListener

            try {
                val provider = providerFuture.get()
                cameraProvider = provider

                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(android.util.Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                cameraExecutor = Executors.newSingleThreadExecutor()
                var lastFrameAt = 0L
                analysis.setAnalyzer(cameraExecutor!!) { image ->
                    try {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastFrameAt >= FRAME_INTERVAL_MS) {
                            lastFrameAt = now
                            encodeFrame(image)?.let { cameraServer?.publishFrame(it) }
                        }
                    } catch (error: Exception) {
                        Log.e(TAG, "Could not encode camera frame", error)
                    } finally {
                        image.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
                statusText.text = "Streaming. Enter $wifiAddress in the dashboard."
            } catch (error: Exception) {
                Log.e(TAG, "Could not start camera preview", error)
                statusText.text = "Could not start camera: ${error.message}"
                stopStreaming()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun encodeFrame(image: ImageProxy): ByteArray? {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val nv21 = ByteArray(width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2))
        var outputIndex = 0

        for (row in 0 until height) {
            val rowStart = yBuffer.position() + row * yPlane.rowStride
            for (column in 0 until width) {
                nv21[outputIndex++] = yBuffer.get(rowStart + column * yPlane.pixelStride)
            }
        }

        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        for (row in 0 until chromaHeight) {
            val uRowStart = uBuffer.position() + row * uPlane.rowStride
            val vRowStart = vBuffer.position() + row * vPlane.rowStride
            for (column in 0 until chromaWidth) {
                nv21[outputIndex++] = vBuffer.get(vRowStart + column * vPlane.pixelStride)
                nv21[outputIndex++] = uBuffer.get(uRowStart + column * uPlane.pixelStride)
            }
        }

        val jpeg = ByteArrayOutputStream()
        val converted = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(Rect(0, 0, width, height), JPEG_QUALITY, jpeg)
        if (!converted) return null

        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return jpeg.toByteArray()

        val jpegBytes = jpeg.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size) ?: return null
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val rotatedJpeg = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, rotatedJpeg)
        if (rotated !== bitmap) rotated.recycle()
        bitmap.recycle()
        return rotatedJpeg.toByteArray()
    }

    private fun stopStreaming() {
        if (!isStreaming && cameraServer == null && cameraExecutor == null) return

        isStreaming = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        cameraExecutor?.shutdownNow()
        cameraExecutor = null
        cameraServer?.close()
        cameraServer = null
        if (::startButton.isInitialized) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            startButton.text = "Start camera"
            wifiButton.isEnabled = true
            statusText.text = "Camera is off"
            refreshWifiAddress()
        }
    }

    private fun refreshWifiAddress() {
        wifiAddress = try {
            val connectivity = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivity.activeNetwork
            val capabilities = network?.let(connectivity::getNetworkCapabilities)
            if (network == null || capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                null
            } else {
                connectivity.getLinkProperties(network)
                    ?.linkAddresses
                    ?.asSequence()
                    ?.map { it.address }
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                    ?.hostAddress
            }
        } catch (error: SecurityException) {
            Log.e(TAG, "Could not read the Wi-Fi address", error)
            null
        }

        if (::addressText.isInitialized) {
            addressText.text = wifiAddress ?: "Not connected to Wi-Fi"
            if (!isStreaming) {
                endpointText.text = if (wifiAddress == null) {
                    "Connect this phone and the dashboard device to the same Wi-Fi."
                } else {
                    "In the dashboard, enter this IP address: $wifiAddress"
                }
            }
            startButton.isEnabled = wifiAddress != null || isStreaming
            copyAddressButton.isEnabled = wifiAddress != null
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "HazoraCamera"
        private const val FRAME_INTERVAL_MS = 250L
        private const val JPEG_QUALITY = 70
    }
}
