package net.codeedu.justwebcam

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.rtspserver.server.ClientListener
import com.pedro.rtspserver.server.RtspServer
import com.pedro.rtspserver.server.ServerClient
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

class RtspStreamService(
    private val context: Context,
    private val clientCountCallback: (Int) -> Unit,
    private val port: Int = StreamConfig.RTSP_PORT
) : StreamService, FrameCallback, AudioCallback, ConnectChecker, ClientListener {

    private var rtspServer: RtspServer? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var isRunning = false
    private var serverStarted = false
    private var videoThread: Thread? = null

    // Frame queue for thread-safe passing from camera callback to encoder (capacity=5)
    private var frameQueue: ArrayBlockingQueue<ByteArray>? = null
    @Volatile
    private var pendingAudio: ShortArray? = null
    @Volatile
    private var pendingAudioLength = 0
    @Volatile
    private var pendingAudioTimestampUs = 0L

    private var i420Buffer: ByteArray? = null
    // Pre-allocated frame pool to avoid GC pressure
    private val framePool = Array(5) { ByteArray(StreamConfig.VIDEO_WIDTH * StreamConfig.VIDEO_HEIGHT * 3 / 2) }
    private var poolIndex = 0

    private val clientCounter = AtomicInteger(0)
    private var videoInfoSet = false

    companion object {
        private const val TAG = "RtspStreamService"
        private const val VIDEO_MIME = "video/avc"
        private const val AUDIO_MIME = "audio/mp4a-latm"
    }

    override fun start() {
        if (isRunning) {
            Log.d(TAG, "RTSP server already running")
            return
        }

        runCatching {
            // Create RTSP server but don't start yet (wait for SPS/PPS)
            rtspServer = RtspServer(this, port).apply {
                setAudioInfo(StreamConfig.RTSP_SAMPLE_RATE, false)
                setClientListener(this@RtspStreamService)
            }

            // Video encoder setup
            val videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, StreamConfig.VIDEO_WIDTH, StreamConfig.VIDEO_HEIGHT).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, StreamConfig.RTSP_VIDEO_BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, StreamConfig.TARGET_FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, StreamConfig.RTSP_I_FRAME_INTERVAL)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar)
            }

            videoCodec = MediaCodec.createEncoderByType(VIDEO_MIME).apply {
                configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }

            // Audio encoder setup
            val audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME, StreamConfig.RTSP_SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, StreamConfig.RTSP_AUDIO_BITRATE)
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            }

            audioCodec = MediaCodec.createEncoderByType(AUDIO_MIME).apply {
                configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            Log.i(TAG, "RTSP audio encoder started")

            // Initialize frame queue with capacity 5
            frameQueue = ArrayBlockingQueue(5)

            isRunning = true
            videoThread = Thread(::videoAudioLoop, "RtspVideoAudio").apply { start() }
            Log.i(TAG, "RTSP server initialized on port $port (waiting for SPS/PPS before accepting connections)")

        }.onFailure { e ->
            Log.e(TAG, "Failed to start RTSP", e)
            cleanup()
            isRunning = false
        }
    }

    private fun videoAudioLoop() {
        val videoCodec = videoCodec ?: return
        val audioCodec = audioCodec ?: return
        val videoBufferInfo = MediaCodec.BufferInfo()
        val audioBufferInfo = MediaCodec.BufferInfo()

        while (isRunning) {
            try {
                processVideoFrame(videoCodec)
                processAudioFrame(audioCodec)
                processVideoOutput(videoCodec, videoBufferInfo)
                processAudioOutput(audioCodec, audioBufferInfo)
                Thread.yield()
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (isRunning) Log.w(TAG, "Video/Audio loop error", e)
            }
        }
    }

    private fun processVideoFrame(codec: MediaCodec) {
        val frame = frameQueue?.poll() ?: return

        codec.dequeueInputBuffer(5000).takeIf { it >= 0 }?.let { inIndex ->
            codec.getInputBuffer(inIndex)?.let { inBuf ->
                val i420 = getI420Buffer(StreamConfig.VIDEO_WIDTH, StreamConfig.VIDEO_HEIGHT)
                nv21ToI420(frame, i420, StreamConfig.VIDEO_WIDTH, StreamConfig.VIDEO_HEIGHT)
                inBuf.clear()
                inBuf.put(i420)
                codec.queueInputBuffer(inIndex, 0, i420.size, System.nanoTime() / 1000, 0)
            }
        }
    }

    private fun processAudioFrame(codec: MediaCodec) {
        pendingAudio?.takeIf { pendingAudioLength > 0 }?.let { audioBuffer ->
            val length = pendingAudioLength
            val timestampUs = pendingAudioTimestampUs

            codec.dequeueInputBuffer(10000).takeIf { it >= 0 }?.let { inIndex ->
                codec.getInputBuffer(inIndex)?.let { inBuf ->
                    inBuf.clear()
                    inBuf.order(java.nio.ByteOrder.nativeOrder())
                    val maxShorts = inBuf.capacity() / 2
                    val processLength = length.coerceAtMost(maxShorts)

                    inBuf.asShortBuffer().put(audioBuffer, 0, processLength)
                    codec.queueInputBuffer(inIndex, 0, processLength * 2, timestampUs, 0)

                    // Handle partial buffer processing
                    if (processLength < length) {
                        pendingAudio = audioBuffer.copyOfRange(processLength, length)
                        pendingAudioLength = length - processLength
                    } else {
                        pendingAudio = null
                        pendingAudioLength = 0
                    }
                }
            }
        }
    }

    private fun processVideoOutput(codec: MediaCodec, bufferInfo: MediaCodec.BufferInfo) {
        var outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                handleVideoFormat(codec)
            } else if (outIndex >= 0) {
                handleOutput(codec, outIndex, bufferInfo, isVideo = true)
            }
            outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    private fun processAudioOutput(codec: MediaCodec, bufferInfo: MediaCodec.BufferInfo) {
        var outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (outIndex >= 0) {
                handleOutput(codec, outIndex, bufferInfo, isVideo = false)
            }
            outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
        }
    }

    private fun handleVideoFormat(codec: MediaCodec) {
        runCatching {
            val outFormat = codec.outputFormat
            val csd0 = outFormat.getByteBuffer("csd-0")
            val csd1 = outFormat.getByteBuffer("csd-1")
            if (csd0 != null && csd1 != null) {
                rtspServer?.run {
                    resizeCache(2000)
                    setVideoInfo(csd0, csd1, null)
                }
                if (!videoInfoSet) {
                    videoInfoSet = true
                    // Now start the RTSP server after SPS/PPS is available
                    if (!serverStarted) {
                        serverStarted = true
                        rtspServer?.startServer()
                        Log.i(TAG, "RTSP server started on port $port (SPS/PPS ready)")
                    }
                    Log.i(TAG, "RTSP video info set, server ready for connections")
                    clientCountCallback(clientCounter.get())
                }
            }
        }.onFailure { Log.e(TAG, "Failed to set video info", it) }
    }

    private fun handleOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo, isVideo: Boolean) {
        rtspServer?.takeIf { serverStarted }?.let { server ->
            codec.getOutputBuffer(index)?.takeIf { info.size > 0 }?.let { outBuf ->
                outBuf.position(info.offset)
                outBuf.limit(info.offset + info.size)
                if (isVideo) server.sendVideo(outBuf, info) else server.sendAudio(outBuf, info)
            }
        }
        codec.releaseOutputBuffer(index, false)
    }

    override fun stop() {
        Log.d(TAG, "Stopping RTSP server...")
        isRunning = false
        videoThread?.interrupt()
        runCatching { videoThread?.join(2000) }

        serverStarted = false
        rtspServer?.stopServer()
        cleanup()
        Log.i(TAG, "RTSP server stopped")
    }

    private fun cleanup() {
        videoCodec?.safeStopRelease()
        audioCodec?.safeStopRelease()
        rtspServer = null
        videoCodec = null
        audioCodec = null
        i420Buffer = null
        frameQueue?.clear()
        frameQueue = null
        pendingAudio = null
        videoThread = null
    }

    // Extension function for safe codec cleanup
    private fun MediaCodec.safeStopRelease() {
        runCatching { stop() }
        runCatching { release() }
    }

    override fun isRunning(): Boolean = isRunning
    override fun getStreamUrl(): String = "rtsp://<ip>:$port"
    override fun getPort(): Int = port
    override fun getProtocol(): String = StreamProtocol.RTSP.displayName

    override fun onNv21Frame(nv21: ByteArray, width: Int, height: Int, timestampUs: Long) {
        if (!isRunning) return
        val queue = frameQueue ?: return

        // Get a buffer from pool (circular)
        val copy = framePool[poolIndex]
        poolIndex = (poolIndex + 1) % framePool.size
        System.arraycopy(nv21, 0, copy, 0, nv21.size)

        // Offer to queue, drop oldest if full
        if (!queue.offer(copy)) {
            queue.poll()  // Remove oldest frame
            queue.offer(copy)  // Retry
        }
    }

    override fun onAudioData(buffer: ShortArray, length: Int, timestampUs: Long) {
        if (!isRunning) return
        
        // Copy audio data to pending buffer for processing in videoAudioLoop
        val copy = ShortArray(length)
        System.arraycopy(buffer, 0, copy, 0, length)
        pendingAudio = copy
        pendingAudioLength = length
        pendingAudioTimestampUs = timestampUs
    }

    private fun getI420Buffer(width: Int, height: Int): ByteArray {
        val size = width * height * 3 / 2
        if (i420Buffer == null || i420Buffer!!.size != size) {
            i420Buffer = ByteArray(size)
        }
        return i420Buffer!!
    }

    private fun nv21ToI420(nv21: ByteArray, i420: ByteArray, width: Int, height: Int) {
        val ySize = width * height
        System.arraycopy(nv21, 0, i420, 0, ySize)
        val uvSize = ySize / 4
        val vOffset = ySize + uvSize
        var pos = 0
        while (pos < ySize / 2) {
            i420[vOffset + pos / 2] = nv21[ySize + pos]
            i420[ySize + pos / 2] = nv21[ySize + pos + 1]
            pos += 2
        }
    }

    override fun onConnectionStarted(url: String) {
        Log.d(TAG, "Connection started: $url")
    }
    override fun onConnectionSuccess() {
        Log.d(TAG, "Connection successful")
    }
    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "Connection failed: $reason")
    }
    override fun onDisconnect() {
        Log.d(TAG, "Disconnected")
    }
    override fun onAuthError() {
        Log.e(TAG, "Auth error")
    }
    override fun onAuthSuccess() {
        Log.i(TAG, "Auth success")
    }
    override fun onNewBitrate(bitrate: Long) {
        Log.d(TAG, "New bitrate: $bitrate")
    }

    override fun onClientConnected(client: ServerClient) {
        Log.i(TAG, "Client connected: ${client.getAddress()}")
        val count = clientCounter.incrementAndGet()
        Log.d(TAG, "Clients total: $count")
        clientCountCallback(count)
    }

    override fun onClientDisconnected(client: ServerClient) {
        Log.i(TAG, "Client disconnected: ${client.getAddress()}")
        val count = clientCounter.decrementAndGet()
        Log.d(TAG, "Clients total: $count")
        clientCountCallback(count)
    }

    override fun onClientNewBitrate(bitrate: Long, client: ServerClient) {
        Log.d(TAG, "New bitrate: $bitrate for client ${client.getAddress()}")
    }
}