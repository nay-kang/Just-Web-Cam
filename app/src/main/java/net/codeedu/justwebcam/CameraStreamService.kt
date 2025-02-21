package net.codeedu.justwebcam

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Paint
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue

class CameraStreamService : Service() {
    private lateinit var cameraId: String
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private lateinit var cameraHandler: Handler
    private var captureSession: CameraCaptureSession? = null
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val frameQueue = LinkedBlockingQueue<ByteArray>(1)
    private lateinit var streamServer: StreamServer
    private val imageProcessingThread = HandlerThread("ImageProcessing").apply { start() }
    private val imageProcessingHandler = Handler(imageProcessingThread.looper)
    private var frameCounter = 0
    private var lastLightLevel = LightLevel.NORMAL // Initialize with bright light

    private enum class LightLevel {
        NORMAL, DEEP_DARK, KEEP
    }

    companion object {
        internal const val TAG = "CameraStreamService"
        private const val NOTIFICATION_CHANNEL_ID = "CameraStreamChannel"
        const val ACTION_START_STREAM =
            "net.codeedu.justwebcam.ACTION_START_STREAM" // Actions for starting/stopping
        const val ACTION_STOP_STREAM = "net.codeedu.justwebcam.ACTION_STOP_STREAM"

        // Brightness thresholds (adjust these based on your testing)
        const val NORMAL_LIGHT_THRESHOLD = 180 // Bright light if average brightness > 180
        const val DEEP_DARK_LIGHT_THRESHOLD = 35 // Deep dark light if average brightness < 35

        // Exposure time values (in nanoseconds)
        const val VERY_LONG_EXPOSURE_TIME_NS = 300_000_000L // 300ms (adjust as needed)

        // FPS ranges
        val DEFAULT_FPS_RANGE = Range(10, 15)// Default FPS range
        const val FRAME_DURATION = 1_000_000_000L / 5 // 5 FPS
        val previewSize = Size(1280, 720)
    }

    override fun onCreate() {
        super.onCreate()
        cameraHandler = Handler(cameraThread.looper)
        streamServer = StreamServer(
            frameQueue = frameQueue,
            clientCountCallback = { clientCount -> // Initialize StreamServer with callback
                onClientCountChanged(clientCount) // Call onClientCountChanged when count changes
            },
            context = this,
            8080
        )

        Log.d(TAG, "Service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand: ${intent?.action}")
        when (intent?.action) {
            ACTION_START_STREAM -> {
                startForegroundService() // Start as foreground service
                streamServer.start()
            }

            ACTION_STOP_STREAM -> {
                stopCameraStreaming()
                stopForegroundService() // Stop foreground service and service itself
                stopSelf() // Stop the service
            }

            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
        }
        return START_STICKY // Or START_NOT_STICKY, depending on your needs
    }

    private fun startForegroundService() {
        createNotificationChannel()
        val notification: Notification =
            createNotification("Streaming video in background")
        startForeground(1, notification) // Start foreground service with notification
        Log.d(TAG, "Foreground service started")
    }

    private fun stopForegroundService() {
        stopForeground(STOP_FOREGROUND_REMOVE) // or STOP_FOREGROUND_DETACH for different behavior
        Log.d(TAG, "Foreground service stopped")
    }

    private fun onClientCountChanged(clientCount: Int) {
        Log.d(TAG, "Client count changed: $clientCount")
        if (clientCount > 0) {
            startCamera() // Start camera when first client connects
        } else {
            stopCamera()  // Stop camera when last client disconnects
        }
    }

    private fun stopCameraStreaming() {
        streamServer.stop()
        stopCamera()
        Log.d(TAG, "Camera streaming stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCameraStreaming() // Ensure everything is stopped on destroy
        cameraThread.quitSafely()
        try {
            cameraThread.join()
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted while waiting for camera thread to join", e)
            Thread.currentThread().interrupt()
        }

        imageProcessingThread.quitSafely()
        try {
            imageProcessingThread.join()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        Log.d(TAG, "Service onDestroy")
    }

    /*
    * using this to restart service will cause
    * "android.hardware.camera2.CameraAccessException: CAMERA_DISABLED (1): connectHelper:1958: Camera "0" disabled by policy"
    * */
    /*
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed (app swiped away)")

        Handler(Looper.getMainLooper()).postDelayed({
            Log.d(TAG, "Attempting service restart after task removed")
            val restartIntent = Intent(this, CameraStreamService::class.java)
            restartIntent.action = ACTION_START_STREAM // Or your service start action
            ContextCompat.startForegroundService(this, restartIntent)
        }, 1000) // 1-second delay - Add a small delay to avoid immediate restart contention

    }*/

    private fun startCamera() {
        if (cameraDevice != null) { // Check if camera is already open
            Log.d(TAG, "Camera already started, not restarting.")
            return
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            setupCamera()
        } else {
            Log.w(TAG, "Camera permission not granted, cannot start camera")
            // TODO inform the Activity.
        }
    }

    private fun stopCamera() {
        captureSession?.closeSafely()
        captureSession = null
        cameraDevice?.closeSafely()
        cameraDevice = null
        imageReader?.closeSafely()
        imageReader = null
    }

    private fun setupCamera() {
        lastLightLevel = LightLevel.NORMAL
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        try {
            val cameraIds = cameraManager.cameraIdList
            cameraId = cameraIds.firstOrNull { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: throw IllegalStateException("Back camera not found")

            imageReader = ImageReader.newInstance(
                previewSize.width,
                previewSize.height,
                ImageFormat.YUV_420_888,
                2
            ).apply {
                setOnImageAvailableListener({ reader ->
                    var image: Image? = null
                    try {
                        image = reader.acquireNextImage()
                        image?.let { img ->
                            imageProcessingHandler.post {
                                processAndQueueFrame(img)
                                img.close()
                            }
                        }
                    } catch (e: IllegalStateException) {
                        Log.e(TAG, "ImageReader buffer full", e)
                    }
                }, cameraHandler)
            }

            cameraManager.openCamera(cameraId, cameraStateCallback, cameraHandler)

        } catch (e: CameraAccessException) {
            Log.e(TAG, "Camera access exception", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Illegal argument exception", e)
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception", e)
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Illegal State Exception: ${e.message}", e)
        }

    }

    private fun getBatteryPercentage(): Int {
        val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun processAndQueueFrame(image: Image) {
        frameCounter++
        if (frameCounter % 15 == 0) { // Calculate brightness every 15 frames
            frameCounter = 0

            // Calculate average brightness on the smaller bitmap
            val averageBrightness = calculateAverageBrightnessOptimized(image)
            Log.d(TAG, "Average Brightness: $averageBrightness")

            // Determine light level category
            val currentLightLevel = when {
                averageBrightness > NORMAL_LIGHT_THRESHOLD -> LightLevel.NORMAL
                averageBrightness < DEEP_DARK_LIGHT_THRESHOLD -> LightLevel.DEEP_DARK
                else -> LightLevel.KEEP
            }

            // Update exposure if needed
            if (currentLightLevel != lastLightLevel && currentLightLevel != LightLevel.KEEP) {
                lastLightLevel = currentLightLevel
                Log.d(TAG, "Adjusting light level to: $currentLightLevel")
                adjustExposure(currentLightLevel)
            }
        }

        var timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        timestamp += " ${getBatteryPercentage()}%\uD83D\uDD0B"
        val bitmap = imageToBitmap(image) ?: return
        image.close()

        // Create a mutable bitmap to draw on
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        bitmap.recycle()

        val canvas = Canvas(mutableBitmap)
        val textPaint = Paint().apply {
            color = Color.WHITE // Main text color is white
            textSize = 40f
            isAntiAlias = true
            style = Paint.Style.FILL // Default style is FILL for the main text
        }

        // Create a Paint for the black border/stroke
        val borderPaint = Paint().apply {
            color = Color.BLACK // Border color is black
            textSize = 40f // Must match textPaint's textSize
            isAntiAlias = true
            style = Paint.Style.STROKE // Style to STROKE for border
            strokeWidth = 5f // Border width (adjust as needed)
        }

        val x = 20f
        val y = 50f

        // Draw black text outline (border) first
        borderPaint.textAlign =
            Paint.Align.LEFT // Align text to the left for consistent positioning
        canvas.drawText(timestamp, x, y, borderPaint)

        // Draw white text on top (fill)
        textPaint.textAlign = Paint.Align.LEFT // Ensure same alignment as border
        canvas.drawText(timestamp, x, y, textPaint)

        val outputStream = ByteArrayOutputStream()
        mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 50, outputStream)
        val modifiedJpegBytes = outputStream.toByteArray()
        outputStream.closeSafely()
        mutableBitmap.recycle()

        frameQueue.offer(modifiedJpegBytes)
    }

    private fun calculateAverageBrightnessOptimized(image: Image): Int {
        val yPlane = image.planes[0]
        val yBuffer = yPlane.buffer
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        val width = image.width
        val height = image.height

        var totalBrightness = 0L
        var sampleCount = 0
        val sampleStep = 8 // Sample every 8th pixel in both dimensions

        for (y in 0 until height step sampleStep) {
            for (x in 0 until width step sampleStep) {
                val pixelIndex = y * rowStride + x * pixelStride
                val yValue = yBuffer.get(pixelIndex).toInt() and 0xFF // Ensure value is unsigned
                totalBrightness += yValue
                sampleCount++
            }
        }

        return if (sampleCount > 0) (totalBrightness / sampleCount).toInt() else 0
    }

    private fun rangeArrayToStr(ranges: Array<Range<Int>>?): String {
        val rangesString =
            ranges?.joinToString(separator = ", ", prefix = "[", postfix = "]") { range ->
                "${range.lower}..${range.upper}"
            }
        return rangesString ?: "null"
    }

    private fun adjustExposure(lightLevel: LightLevel) {

        captureSession?.let { session ->
            try {
                var requestBuilder: CaptureRequest.Builder? = null
                if (lightLevel == LightLevel.DEEP_DARK) {
                    val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
                    val exposureTimeRange =
                        cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    val fpsRanges =
                        cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    val isoRange =
                        cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    Log.d(
                        TAG,
                        "Exposure time range: $exposureTimeRange, FPS ranges: ${
                            rangeArrayToStr(fpsRanges)
                        }"
                    )
                    if (exposureTimeRange == null || fpsRanges == null) {
                        Log.e(TAG, "Exposure time range or FPS ranges not supported")
                        return
                    }
                    val desiredExposure = VERY_LONG_EXPOSURE_TIME_NS.coerceIn(
                        exposureTimeRange.lower,
                        exposureTimeRange.upper
                    )
                    requestBuilder =
                        cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            ?.apply {
                                addTarget(imageReader!!.surface)
                                set(
                                    CaptureRequest.CONTROL_SCENE_MODE,
                                    CaptureRequest.CONTROL_SCENE_MODE_NIGHT
                                )
                                set(
                                    CaptureRequest.CONTROL_AE_MODE,
                                    CaptureRequest.CONTROL_AE_MODE_OFF
                                ) // Turn off auto-exposure
                                set(
                                    CaptureRequest.SENSOR_EXPOSURE_TIME,
                                    desiredExposure
                                ) // Set exposure time
                                set(
                                    CaptureRequest.NOISE_REDUCTION_MODE,
                                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                                )
                                set(CaptureRequest.SENSOR_SENSITIVITY, isoRange?.upper ?: 800)
                                set(CaptureRequest.SENSOR_FRAME_DURATION, FRAME_DURATION)

                            }
                } else {
                    requestBuilder =
                        cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                            ?.apply {
                                addTarget(imageReader!!.surface)
                                set(
                                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                    DEFAULT_FPS_RANGE
                                )
                            }
                }

                requestBuilder?.let {
                    session.setRepeatingRequest(
                        it.build(),
                        null,
                        cameraHandler
                    )
                }
            } catch (e: CameraAccessException) {
                Log.e(TAG, "Failed to adjust exposure", e)
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Failed to adjust exposure", e)
            }
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(
            nv21,
            ImageFormat.NV21,
            image.width,
            image.height,
            null
        )

        ByteArrayOutputStream().use { out -> // Use 'use' to auto-close ByteArrayOutputStream
            yuvImage.compressToJpeg(
                android.graphics.Rect(0, 0, image.width, image.height),
                90,
                out
            )
            val imageBytes = out.toByteArray()

            val decodedBitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            return decodedBitmap?.copy(Bitmap.Config.ARGB_8888, true)?.apply {
                decodedBitmap.recycle() // Recycle original decodedBitmap
            } ?: run {
                Log.e(TAG, "Failed to decode image bytes into Bitmap")
                null
            }
        }
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice) {
            cameraDevice = camera
            createCaptureSession()
        }

        override fun onDisconnected(camera: CameraDevice) {
            camera.close()
            cameraDevice = null
            Log.w(TAG, "Camera disconnected")
        }

        override fun onError(camera: CameraDevice, error: Int) {
            camera.close()
            cameraDevice = null
            val errorDescription = when (error) {
                ERROR_CAMERA_DEVICE -> "Device-level error"
                ERROR_CAMERA_DISABLED -> "Camera disabled by policy"
                ERROR_CAMERA_IN_USE -> "Camera in use"
                ERROR_CAMERA_SERVICE -> "Camera service error"
                ERROR_MAX_CAMERAS_IN_USE -> "Max cameras in use"
                else -> "Unknown error code: $error"
            }
            Log.e(TAG, "Camera error: $error, Description: $errorDescription")
        }
    }

    private fun createCaptureSession() {
        cameraDevice?.let { device ->
            imageReader?.let { reader ->
                try {
                    val outputConfigurations = listOf(
                        OutputConfiguration(reader.surface)
                    )
                    val sessionConfiguration = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        outputConfigurations,
                        Executors.newSingleThreadExecutor(),
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) {
                                captureSession = session
                                startCaptureSession()
                            }

                            override fun onConfigureFailed(session: CameraCaptureSession) {
                                Log.e(TAG, "Failed to configure capture session")
                            }
                        }
                    )
                    device.createCaptureSession(sessionConfiguration)

                } catch (e: CameraAccessException) {
                    Log.e(TAG, "Capture session creation exception", e)
                }
            } ?: Log.e(TAG, "ImageReader not initialized")
        } ?: Log.e(TAG, "CameraDevice not opened")
    }

    private fun startCaptureSession() {
        captureSession?.let { session ->
            imageReader?.surface?.let { surface ->
                try {

                    val requestBuilder =
                        session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(surface)
                            set(
                                CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                                DEFAULT_FPS_RANGE
                            )
                        }
                    session.setRepeatingRequest(
                        requestBuilder.build(),
                        null,
                        cameraHandler
                    )
                } catch (e: CameraAccessException) {
                    Log.e(TAG, "Repeating capture request exception", e)
                }
            } ?: Log.e(TAG, "ImageReader surface is null")
        } ?: Log.e(TAG, "CaptureSession not initialized")
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Camera Stream Service Channel",
            NotificationManager.IMPORTANCE_LOW // or IMPORTANCE_LOW for less intrusive
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(message: String): Notification {
        val notificationIntent =
            Intent(this, MainActivity::class.java) // Open MainActivity if notification tapped
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE // Recommended flag for pending intents
        )

        val stopIntent = Intent(this, CameraStreamService::class.java).apply {
            action = ACTION_STOP_STREAM // Action to stop the service
        }
        val stopPendingIntent: PendingIntent =
            PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Camera Streaming")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher) // Replace with your notification icon
            .setContentIntent(pendingIntent)
            .addAction(
                R.drawable.ic_launcher_foreground,
                "Stop Stream",
                stopPendingIntent
            ) // Stop action
            .setOngoing(true) // Make notification non-dismissible by swipe
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // Services that are started only, not bound, return null
    }

}


private fun CameraDevice.closeSafely() {
    try {
        this.close()
    } catch (e: Exception) {
        Log.e(CameraStreamService.TAG, "Error closing camera device", e)
    }
}

private fun ImageReader.closeSafely() {
    try {
        this.close()
    } catch (e: Exception) {
        Log.e(CameraStreamService.TAG, "Error closing image reader", e)
    }
}

private fun CameraCaptureSession.closeSafely() {
    try {
        this.close()
    } catch (e: Exception) {
        Log.e(CameraStreamService.TAG, "Error closing capture session", e)
    }
}

fun ByteArrayOutputStream.closeSafely() {
    try {
        this.close()
    } catch (e: IOException) {
        Log.e(CameraStreamService.TAG, "Error closing ByteArrayOutputStream: ${e.message}")
    }
}