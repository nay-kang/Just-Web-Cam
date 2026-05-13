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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.Image
import android.media.ImageReader
import android.media.MediaRecorder
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
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CameraStreamService : Service() {
    private lateinit var cameraId: String
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private lateinit var cameraHandler: Handler
    private var captureSession: CameraCaptureSession? = null
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val frameQueue = LinkedBlockingQueue<ByteArray>(1)
    private lateinit var mjpegService: StreamService
    private lateinit var rtspService: StreamService
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

    // Cache components for efficient overlay
    private var nv21Buffer: ByteArray? = null
    private var timestampBitmap: Bitmap? = null
    private var timestampCanvas: Canvas? = null
    private var timestampPixels: IntArray? = null  // Cache for bitmap pixels
    private var lastTimestampString: String? = null
    private var cachedBattery = 0
    private var lastBatteryUpdate = 0L

    // Reuse ByteArrayOutputStream
    private val jpegOutputStream = ByteArrayOutputStream()

    // Frame callback for RTSP raw frame delivery
    private var frameCallback: FrameCallback? = null

    // Audio callback for RTSP audio delivery
    private var audioCallback: AudioCallback? = null

    // Audio capture (raw PCM)
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var isAudioRunning = false

    // Cache SimpleDateFormat
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())

    private val mjpegClientCount = AtomicInteger(0)
    private val rtspClientCount = AtomicInteger(0)

    companion object {
        internal const val TAG = "CameraStreamService"
        private const val NOTIFICATION_CHANNEL_ID = "CameraStreamChannel"
        const val ACTION_START_STREAM =
            "net.codeedu.justwebcam.ACTION_START_STREAM" // Actions for starting/stopping
        const val ACTION_STOP_STREAM = "net.codeedu.justwebcam.ACTION_STOP_STREAM"

        const val VIDEO_WIDTH = 1280
        const val VIDEO_HEIGHT = 720
        const val TARGET_FPS = 30

        // Adaptive FPS ranges
        val FPS_RANGE = Range(5, TARGET_FPS)

        val previewSize = Size(VIDEO_WIDTH, VIDEO_HEIGHT)

        // Audio settings
        const val AUDIO_SAMPLE_RATE = 32000

        const val ACTION_UPDATE_TIMESTAMP_STATE =
            "net.codeedu.justwebcam.ACTION_UPDATE_TIMESTAMP_STATE"
        const val EXTRA_SHOW_TIMESTAMP = "net.codeedu.justwebcam.EXTRA_SHOW_TIMESTAMP"
    }

    override fun onCreate() {
        super.onCreate()
        cameraHandler = Handler(cameraThread.looper)
        exposureLogger = Executors.newSingleThreadScheduledExecutor()
        captureCallback = createCaptureCallback()
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
        mjpegService = createMjpegService()
        rtspService = createRtspService()
        Log.d(TAG, "Service onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand: ${intent?.action}")
        when (intent?.action) {
            ACTION_START_STREAM -> {
                startForegroundService()
                showTimestampOverlay = intent.getBooleanExtra(EXTRA_SHOW_TIMESTAMP, true)
                startExposureLogging()
                mjpegService.start()
                rtspService.start()
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
        val notification = createNotification("Streaming video in background")
        startForeground(1, notification)
        Log.d(TAG, "Foreground service started")

        getBatteryPercentageCached() // pre-warm battery cache before camera produces frames
        startCamera()
    }

    private fun stopForegroundService() {
        stopForeground(STOP_FOREGROUND_REMOVE) // or STOP_FOREGROUND_DETACH for different behavior
        Log.d(TAG, "Foreground service stopped")
    }

    private fun updateStreamState() {
        val total = mjpegClientCount.get() + rtspClientCount.get()
        Log.d(TAG, "Total clients: $total (MJPEG: ${mjpegClientCount.get()}, RTSP: ${rtspClientCount.get()})")
        if (total > 0) {
            startCamera()
            startAudio()
        } else {
            stopCamera()
            stopAudio()
        }
    }

    private fun createMjpegService(): StreamService {
        frameCallback = null
        audioCallback = null
        return StreamServiceFactory.createMjpegStreamService(
            frameQueue = frameQueue,
            clientCountCallback = { count ->
                mjpegClientCount.set(count)
                cameraHandler.post { updateStreamState() }
            },
            context = this,
            8080
        )
    }

    private fun createRtspService(): StreamService {
        val svc = StreamServiceFactory.createRtspStreamService(
            this,
            { count ->
                rtspClientCount.set(count)
                cameraHandler.post { updateStreamState() }
            },
            1935
        )
        frameCallback = svc as FrameCallback
        audioCallback = svc as AudioCallback
        return svc
    }

    private fun startAudio() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "RECORD_AUDIO permission not granted, audio will be disabled")
            return
        }

        runCatching {
            val minBuf = AudioRecord.getMinBufferSize(
                AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(2048)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AUDIO_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf
            ).apply { startRecording() }

            isAudioRunning = true
            audioThread = Thread(::audioLoop, "CameraAudio").apply { start() }
            Log.i(TAG, "Audio capture started")

        }.onFailure { e ->
            Log.e(TAG, "Failed to start audio", e)
            stopAudio()
        }
    }

    private fun stopAudio() {
        isAudioRunning = false
        audioThread?.interrupt()
        runCatching { audioThread?.join(2000) }

        audioRecord?.safeStopRelease()
        audioRecord = null
        audioThread = null
        Log.d(TAG, "Audio capture stopped")
    }

    private fun audioLoop() {
        val record = audioRecord ?: return
        val pcmSize = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)
        val pcmBuffer = ShortArray(pcmSize / 2)
        var presentationTimeUs = System.nanoTime() / 1000

        while (isAudioRunning) {
            try {
                val read = record.read(pcmBuffer, 0, pcmBuffer.size)
                if (read <= 0) continue

                audioCallback?.onAudioData(pcmBuffer, read, presentationTimeUs)
                presentationTimeUs += read * 1_000_000L / AUDIO_SAMPLE_RATE
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (isAudioRunning) Log.w(TAG, "Audio loop error", e)
            }
        }
    }

    private fun stopCameraStreaming() {
        stopExposureLogging()
        mjpegService.stop()
        rtspService.stop()
        stopAudio()
        stopCamera()
        Log.d(TAG, "Camera streaming stopped")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopCameraStreaming()

        cameraThread.quitSafely()
        runCatching { cameraThread.join() }.onFailure { e ->
            Log.e(TAG, "Interrupted while waiting for camera thread", e)
            Thread.currentThread().interrupt()
        }

        imageProcessingThread.quitSafely()
        runCatching { imageProcessingThread.join() }.onFailure {
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
        if (cameraDevice != null) {
            Log.d(TAG, "Camera already started")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            setupCamera()
        } else {
            Log.w(TAG, "Camera permission not granted")
        }
    }

    private fun stopCamera() {
        captureSession?.safeClose()
        captureSession = null
        cameraDevice?.safeClose()
        cameraDevice = null
        imageReader?.safeClose()
        imageReader = null
    }

    private fun setupCamera() {
        try {
            cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: throw IllegalStateException("Back camera not found")

            imageReader = ImageReader.newInstance(
                previewSize.width, previewSize.height,
                ImageFormat.YUV_420_888, 5
            ).apply {
                setOnImageAvailableListener({ reader ->
                    runCatching {
                        reader.acquireLatestImage()?.let { img ->
                            imageProcessingHandler.post {
                                processAndQueueFrame(img)
                                img.close()
                            }
                        }
                    }.onFailure { e ->
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
        textAlign = Paint.Align.LEFT
    }

    private val borderPaint = Paint().apply {
        color = Color.BLACK
        textSize = 40f
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 5f
        textAlign = Paint.Align.LEFT
    }

    private fun getBatteryPercentageCached(): Int {
        val now = System.currentTimeMillis()
        if (now - lastBatteryUpdate > 10000) {
            cachedBattery = getBatteryPercentage()
            lastBatteryUpdate = now
        }
        return cachedBattery
    }

    private fun processAndQueueFrame(image: Image) {
        val width = image.width
        val height = image.height
        val nv21 = imageToNv21(image)

        if (showTimestampOverlay) {
            val timestamp = timestampFormat.format(Date())
            val battery = getBatteryPercentageCached()
            drawOverlayOnNv21(nv21, width, height, "$timestamp BAT: $battery%")
        }

        frameCallback?.onNv21Frame(nv21, width, height, System.nanoTime() / 1000)

        jpegOutputStream.reset()
        android.graphics.YuvImage(nv21, ImageFormat.NV21, width, height, null)
            .compressToJpeg(android.graphics.Rect(0, 0, width, height), 50, jpegOutputStream)
        frameQueue.offer(jpegOutputStream.toByteArray())
    }

    private fun getNv21Buffer(width: Int, height: Int): ByteArray {
        val size = width * height * 3 / 2
        if (nv21Buffer == null || nv21Buffer!!.size != size) {
            nv21Buffer = ByteArray(size)
        }
        return nv21Buffer!!
    }

    private fun imageToNv21(image: Image): ByteArray {
        val width = image.width
        val height = image.height
        val nv21 = getNv21Buffer(width, height)

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride

        if (yRowStride == width) {
            yBuffer.get(nv21, 0, width * height)
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, row * width, width)
            }
        }

        var pos = width * height
        for (row in 0 until height / 2) {
            val rowOffset = row * uvRowStride
            for (col in 0 until width / 2) {
                nv21[pos++] = vBuffer.get(rowOffset + col * uvPixelStride)
                nv21[pos++] = uBuffer.get(rowOffset + col * uvPixelStride)
            }
        }
        return nv21
    }

    private fun drawOverlayOnNv21(nv21: ByteArray, width: Int, height: Int, label: String) {
        if (lastTimestampString != label) {
            lastTimestampString = label
            if (timestampBitmap == null) {
                timestampBitmap = createBitmap(550, 60)
                timestampCanvas = Canvas(timestampBitmap!!)
                timestampPixels = IntArray(550 * 60)
            }
            timestampBitmap!!.eraseColor(Color.TRANSPARENT)
            timestampCanvas!!.drawText(label, 10f, 45f, borderPaint)
            timestampCanvas!!.drawText(label, 10f, 45f, textPaint)

            // Only update pixels when text changes
            timestampBitmap!!.getPixels(timestampPixels!!, 0, timestampBitmap!!.width, 0, 0, timestampBitmap!!.width, timestampBitmap!!.height)
        }

        val pixels = timestampPixels!!
        val overlayWidth = timestampBitmap!!.width
        val overlayHeight = timestampBitmap!!.height

        for (i in 0 until overlayHeight) {
            for (j in 0 until overlayWidth) {
                val p = pixels[i * overlayWidth + j]
                if ((p ushr 24) > 128) {
                    val yIdx = (20 + i) * width + (20 + j)
                    if (yIdx < width * height) {
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        // Integer arithmetic: Y = (77*R + 150*G + 29*B) / 256
                        nv21[yIdx] = ((77 * r + 150 * g + 29 * b) shr 8).toByte()
                    }
                }
            }
        }
    }

    private fun startExposureLogging() {
        exposureLogger.scheduleWithFixedDelay({ logCurrentExposureSettings() }, 0, 10, TimeUnit.SECONDS)
    }

    private fun logCurrentExposureSettings() {
        Log.d(TAG, "Last captured exposure - Time: ${lastLoggedExposureTime ?: "N/A"} ns, ISO: ${lastLoggedIso ?: "N/A"}")

        runCatching {
            val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
            val exposureTimeRange = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val isoRange = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)

            Log.d(TAG, "Available Ranges - Exposure: ${exposureTimeRange?.lower}-${exposureTimeRange?.upper} ns, ISO: ${isoRange?.lower}-${isoRange?.upper}")
        }.onFailure { e ->
            Log.e(TAG, "Failed to get camera characteristics", e)
        }
    }

    private fun createCaptureCallback(): CameraCaptureSession.CaptureCallback {
        return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest,
                result: android.hardware.camera2.TotalCaptureResult) {
                super.onCaptureCompleted(session, request, result)

                val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)

                lastLoggedExposureTime = exposureTime
                lastLoggedIso = iso

                val currentTime = System.currentTimeMillis()
                val shouldLog = currentTime - lastLogTime > 10000
                val exposureChanged = lastLoggedExposureTime != null && exposureTime != null &&
                    kotlin.math.abs(exposureTime - lastLoggedExposureTime!!) > EXPOSURE_CHANGE_THRESHOLD_NS
                val isoChanged = lastLoggedIso != null && iso != null &&
                    kotlin.math.abs(iso - lastLoggedIso!!) > ISO_CHANGE_THRESHOLD

                if (shouldLog || exposureChanged || isoChanged) {
                    Log.d(TAG, "AE Mode: ${result.get(CaptureResult.CONTROL_AE_MODE)}, " +
                        "Exposure: ${exposureTime}ns (${exposureTime?.div(1_000_000)}ms), ISO: $iso")
                    lastLogTime = currentTime
                }
            }
        }
    }

    private fun stopExposureLogging() {
        exposureLogger.shutdown()
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
                runCatching {
                    val outputConfigurations = listOf(OutputConfiguration(reader.surface))
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
                }.onFailure { e ->
                    Log.e(TAG, "Capture session creation exception", e)
                }
            } ?: Log.e(TAG, "ImageReader not initialized")
        } ?: Log.e(TAG, "CameraDevice not opened")
    }

    private fun startCaptureSession() {
        captureSession?.let { session ->
            imageReader?.surface?.let { surface ->
                runCatching {
                    val requestBuilder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(surface)
                        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, FPS_RANGE)

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                            set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY)
                        }
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                        set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_NIGHT)
                        set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
                        set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)

                        cameraManager.getCameraCharacteristics(cameraId)
                            .get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { range ->
                                if (range.upper >= 1) {
                                    set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, minOf(1, range.upper))
                                }
                            }
                    }
                    session.setRepeatingRequest(requestBuilder.build(), captureCallback, cameraHandler)
                }.onFailure { e ->
                    Log.e(TAG, "Repeating capture request exception", e)
                }
            } ?: Log.e(TAG, "ImageReader surface is null")
        } ?: Log.e(TAG, "CaptureSession not initialized")
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Camera Stream Service Channel",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(serviceChannel)
    }

    private fun createNotification(message: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val stopIntent = Intent(this, CameraStreamService::class.java).apply {
            action = ACTION_STOP_STREAM
        }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Camera Streaming")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_launcher_foreground, "Stop Stream", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

private fun CameraDevice.safeClose() = runCatching { close() }.onFailure {
    Log.e(CameraStreamService.TAG, "Error closing camera device", it)
}

private fun ImageReader.safeClose() = runCatching { close() }.onFailure {
    Log.e(CameraStreamService.TAG, "Error closing image reader", it)
}

private fun CameraCaptureSession.safeClose() = runCatching { close() }.onFailure {
    Log.e(CameraStreamService.TAG, "Error closing capture session", it)
}

private fun AudioRecord.safeStopRelease() {
    runCatching { stop() }
    runCatching { release() }
}
