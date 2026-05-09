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
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.BatteryManager
import android.os.Build
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
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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
    private lateinit var exposureLogger: ScheduledExecutorService
    private var captureCallback: CameraCaptureSession.CaptureCallback? = null
    private var lastLoggedExposureTime: Long? = null
    private var lastLoggedIso: Int? = null
    private var lastLogTime: Long = 0
    private val EXPOSURE_CHANGE_THRESHOLD_NS = 10_000_000L // 10ms threshold for logging changes
    private val ISO_CHANGE_THRESHOLD = 50 // ISO threshold for logging changes

    private var showTimestampOverlay = true

    // Reuse ByteArrayOutputStream
    private val jpegOutputStream = ByteArrayOutputStream()

    // Cache SimpleDateFormat
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    companion object {
        internal const val TAG = "CameraStreamService"
        private const val NOTIFICATION_CHANNEL_ID = "CameraStreamChannel"
        const val ACTION_START_STREAM =
            "net.codeedu.justwebcam.ACTION_START_STREAM" // Actions for starting/stopping
        const val ACTION_STOP_STREAM = "net.codeedu.justwebcam.ACTION_STOP_STREAM"

        // Adaptive FPS ranges
        val FPS_RANGE = Range(5, 30) // Normal light: high FPS

        val previewSize = Size(1280, 720)

        const val ACTION_UPDATE_TIMESTAMP_STATE =
            "net.codeedu.justwebcam.ACTION_UPDATE_TIMESTAMP_STATE"
        const val EXTRA_SHOW_TIMESTAMP = "net.codeedu.justwebcam.EXTRA_SHOW_TIMESTAMP"
    }

    override fun onCreate() {
        super.onCreate()
        cameraHandler = Handler(cameraThread.looper)
        exposureLogger = Executors.newSingleThreadScheduledExecutor()
        captureCallback = createCaptureCallback()
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
                showTimestampOverlay = intent.getBooleanExtra(EXTRA_SHOW_TIMESTAMP, true)
                startExposureLogging()
                streamServer.start()
            }

            ACTION_STOP_STREAM -> {
                stopCameraStreaming()
                stopForegroundService() // Stop foreground service and service itself
                stopSelf() // Stop the service
            }

            ACTION_UPDATE_TIMESTAMP_STATE -> {
                showTimestampOverlay = intent.getBooleanExtra(EXTRA_SHOW_TIMESTAMP, true)
                Log.d(TAG, "Timestamp overlay state updated to: $showTimestampOverlay")
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
        stopExposureLogging()
        streamServer.stop()
        stopCamera()
        Log.d(TAG, "Camera streaming stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopExposureLogging()
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
        } catch (_: InterruptedException) {
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
                    try {
                        val image = reader.acquireLatestImage()
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

    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
        style = Paint.Style.FILL
        textAlign = Paint.Align.LEFT // Initialize alignment
    }
    private val borderPaint = Paint().apply {
        color = Color.BLACK
        textSize = 40f
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 5f
        textAlign = Paint.Align.LEFT // Initialize alignment
    }

    private fun processAndQueueFrame(image: Image) {
        val mutableBitmap = imageToMutableBitmap(image) ?: return

        if (showTimestampOverlay) {
            val canvas = Canvas(mutableBitmap)
            val timestamp = timestampFormat.format(Date())
            val label = "$timestamp ${getBatteryPercentage()}%\uD83D\uDD0B"
            canvas.drawText(label, 20f, 50f, borderPaint)
            canvas.drawText(label, 20f, 50f, textPaint)
        }

        jpegOutputStream.reset()
        mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 50, jpegOutputStream)
        val finalJpegBytes = jpegOutputStream.toByteArray()

        mutableBitmap.recycle()
        frameQueue.offer(finalJpegBytes)
    }


    private fun startExposureLogging() {
        exposureLogger.scheduleWithFixedDelay({
            logCurrentExposureSettings()
        }, 0, 10, TimeUnit.SECONDS)
    }

    private fun logCurrentExposureSettings() {
        // Log the last captured exposure values
        Log.d(TAG, "Last captured exposure - Time: ${lastLoggedExposureTime ?: "N/A"} ns, ISO: ${lastLoggedIso ?: "N/A"}")
        
        // Also log ranges for reference
        try {
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            val exposureTimeRange = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val isoRange = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            
            Log.d(TAG, "Available Ranges - Exposure: ${exposureTimeRange?.lower?.toString() ?: "N/A"}-${exposureTimeRange?.upper?.toString() ?: "N/A"} ns, ISO: ${isoRange?.lower?.toString() ?: "N/A"}-${isoRange?.upper?.toString() ?: "N/A"}")
        } catch (e: CameraAccessException) {
            Log.e(TAG, "Failed to get camera characteristics", e)
        }
    }

    private fun createCaptureCallback(): CameraCaptureSession.CaptureCallback {
        return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: android.hardware.camera2.TotalCaptureResult) {
                super.onCaptureCompleted(session, request, result)
                
                // Get the actual exposure values from the capture result
                val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
                val aeMode = result.get(CaptureResult.CONTROL_AE_MODE)
                
                // Update the last known values
                lastLoggedExposureTime = exposureTime
                lastLoggedIso = iso
                
                // Log when values change significantly or every 10 seconds
                val currentTime = System.currentTimeMillis()
                val shouldLog = currentTime - lastLogTime > 10000 // Force log every 10 seconds
                
                val exposureChanged = lastLoggedExposureTime != null && exposureTime != null && 
                    kotlin.math.abs(exposureTime - lastLoggedExposureTime!!) > EXPOSURE_CHANGE_THRESHOLD_NS
                val isoChanged = lastLoggedIso != null && iso != null && 
                    kotlin.math.abs(iso - lastLoggedIso!!) > ISO_CHANGE_THRESHOLD
                
                if (shouldLog || exposureChanged || isoChanged) {
                    Log.d(TAG, "AE Mode: $aeMode, Exposure: ${exposureTime}ns (${exposureTime?.let { it/1_000_000 } ?: "N/A"}ms), ISO: $iso")
                    lastLogTime = currentTime
                }
            }
        }
    }

    private fun stopExposureLogging() {
        exposureLogger.shutdown()
    }

    private fun imageToMutableBitmap(image: Image): Bitmap? {
        if (image.format != ImageFormat.YUV_420_888) {
            Log.e(TAG, "Unexpected image format: ${image.format}")
            return null
        }
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

        jpegOutputStream.reset()
        try {
            yuvImage.compressToJpeg(
                android.graphics.Rect(0, 0, image.width, image.height),
                90,
                jpegOutputStream
            )
            val imageBytes = jpegOutputStream.toByteArray()

            val options = BitmapFactory.Options().apply {
                inMutable = true
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, options)
        } catch (e: Exception) {
            Log.e(TAG, "Error during YUV->Bitmap conversion", e)
            return null
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
                                FPS_RANGE
                            )

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                                set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY)
                            }
                            set(
                                CaptureRequest.CONTROL_MODE,
                                CaptureRequest.CONTROL_MODE_USE_SCENE_MODE
                            )
                            set(
                                CaptureRequest.CONTROL_SCENE_MODE,
                                CaptureRequest.CONTROL_SCENE_MODE_NIGHT
                            )
                            set(
                                CaptureRequest.NOISE_REDUCTION_MODE,
                                CaptureRequest.NOISE_REDUCTION_MODE_FAST
                            )
                            set(
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
                            )
                            val compensationRange = cameraManager.getCameraCharacteristics(cameraId)
                                .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
                            if (compensationRange != null && compensationRange.upper >= 1) {
                                set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, minOf(1, compensationRange.upper))
                            }
                        }
                    session.setRepeatingRequest(
                        requestBuilder.build(),
                        captureCallback,
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