package net.codeedu.justwebcam

/**
 * Centralized configuration for streaming services.
 * All configurable values should be defined here to avoid hardcoding.
 */
object StreamConfig {
    // Port numbers
    const val MJPEG_PORT = 8080
    const val RTSP_PORT = 1935
    
    // Video settings
    const val VIDEO_WIDTH = 1280
    const val VIDEO_HEIGHT = 720
    const val TARGET_FPS = 30
    const val JPEG_QUALITY = 50
    
    // RTSP settings
    const val RTSP_VIDEO_BITRATE = 2_000_000
    const val RTSP_I_FRAME_INTERVAL = 1
    const val RTSP_SAMPLE_RATE = 32000
    const val RTSP_AUDIO_BITRATE = 64000
}
