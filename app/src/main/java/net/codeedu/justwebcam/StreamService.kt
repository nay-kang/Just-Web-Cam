package net.codeedu.justwebcam

import android.content.Context

/**
 * Abstract interface for streaming services.
 * Defines the common contract for different streaming protocols (MJPEG, RTSP, etc.)
 */
interface StreamService {
    /**
     * Start the streaming service
     */
    fun start()
    
    /**
     * Stop the streaming service
     */
    fun stop()
    
    /**
     * Check if the service is currently running
     */
    fun isRunning(): Boolean
    
    /**
     * Get the stream URL that clients can use to connect
     */
    fun getStreamUrl(): String
    
    /**
     * Get the port number the service is running on
     */
    fun getPort(): Int
    
    /**
     * Get the protocol type (e.g., "MJPEG", "RTSP")
     */
    fun getProtocol(): String
}

/**
 * Factory for creating different types of stream services
 */
object StreamServiceFactory {
    fun createMjpegStreamService(
        frameQueue: java.util.concurrent.LinkedBlockingQueue<ByteArray>,
        clientCountCallback: (Int) -> Unit,
        context: Context,
        port: Int = StreamConfig.MJPEG_PORT
    ): StreamService {
        return MjpegStreamService(frameQueue, clientCountCallback, context, port)
    }

    fun createRtspStreamService(
        context: Context,
        clientCountCallback: (Int) -> Unit,
        port: Int = StreamConfig.RTSP_PORT
    ): StreamService {
        return RtspStreamService(context, clientCountCallback, port)
    }
}

/**
 * Callback for delivering NV21 frames to stream services that need raw YUV data
 */
interface FrameCallback {
    fun onNv21Frame(nv21: ByteArray, width: Int, height: Int, timestampUs: Long)
}

/**
 * Callback for delivering raw PCM audio data to stream services
 */
interface AudioCallback {
    fun onAudioData(buffer: ShortArray, length: Int, timestampUs: Long)
}

/**
 * Enum for supported streaming protocols
 */
enum class StreamProtocol(val displayName: String) {
    MJPEG("MJPEG"),
    RTSP("RTSP")
}