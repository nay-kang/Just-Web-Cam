package net.codeedu.justwebcam

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.rtspserver.server.RtspServer

class RtspStreamService(
    private val context: Context,
    private val port: Int = 1935
) : StreamService, FrameCallback, AudioCallback, ConnectChecker {

    private var rtspServer: RtspServer? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var isRunning = false
    private var serverStarted = false
    private var videoThread: Thread? = null

    @Volatile
    private var pendingFrame: ByteArray? = null
    @Volatile
    private var pendingAudio: ShortArray? = null
    @Volatile
    private var pendingAudioLength = 0
    @Volatile
    private var pendingAudioTimestampUs = 0L
    
    private var i420Buffer: ByteArray? = null
    private val framePool = Array(2) { ByteArray(CameraStreamService.VIDEO_WIDTH * CameraStreamService.VIDEO_HEIGHT * 3 / 2) }
    private var poolIndex = 0

    companion object {
        private const val TAG = "RtspStreamService"
        private const val VIDEO_MIME = "video/avc"
        private const val VIDEO_BITRATE = 2_000_000
        private const val SAMPLE_RATE = 32000
        private const val I_FRAME_INTERVAL = 1
        private const val AUDIO_MIME = "audio/mp4a-latm"
    }

    override fun start() {
        if (isRunning) {
            Log.d(TAG, "RTSP server already running")
            return
        }

        try {
            rtspServer = RtspServer(this, port)
            rtspServer!!.setAudioInfo(SAMPLE_RATE, false)

            val videoFormat = MediaFormat.createVideoFormat(VIDEO_MIME, CameraStreamService.VIDEO_WIDTH, CameraStreamService.VIDEO_HEIGHT)
            videoFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            videoFormat.setInteger(MediaFormat.KEY_FRAME_RATE, CameraStreamService.TARGET_FPS)
            videoFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            videoFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            )

            videoCodec = MediaCodec.createEncoderByType(VIDEO_MIME)
            videoCodec!!.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            videoCodec!!.start()

            // Initialize audio encoder (audio capture is in CameraStreamService)
            val audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, 1)
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 64000)
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)

            audioCodec = MediaCodec.createEncoderByType(AUDIO_MIME)
            audioCodec!!.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioCodec!!.start()
            Log.i(TAG, "RTSP audio encoder started")

            isRunning = true
            serverStarted = false

            videoThread = Thread(::videoAudioLoop, "RtspVideoAudio")
            videoThread!!.start()

            Log.i(TAG, "RTSP video encoder started")
        } catch (e: Exception) {
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
                // Process video frames
                val frame = pendingFrame
                if (frame != null) {
                    pendingFrame = null
                    val inIndex = videoCodec.dequeueInputBuffer(5000)
                    if (inIndex >= 0) {
                        val inBuf = videoCodec.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            val i420 = getI420Buffer(CameraStreamService.VIDEO_WIDTH, CameraStreamService.VIDEO_HEIGHT)
                            nv21ToI420(frame, i420, CameraStreamService.VIDEO_WIDTH, CameraStreamService.VIDEO_HEIGHT)
                            inBuf.clear()
                            inBuf.put(i420)
                            videoCodec.queueInputBuffer(
                                inIndex, 0, i420.size,
                                System.nanoTime() / 1000, 0
                            )
                        }
                    }
                }

                // Process audio from CameraStreamService
                val audioBuffer = pendingAudio
                if (audioBuffer != null && pendingAudioLength > 0) {
                    val length = pendingAudioLength
                    val timestampUs = pendingAudioTimestampUs

                    val inIndex = audioCodec.dequeueInputBuffer(10000)
                    if (inIndex >= 0) {
                        val inBuf = audioCodec.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            inBuf.clear()
                            inBuf.order(java.nio.ByteOrder.nativeOrder())
                            
                            // Only process as much as the buffer can hold
                            val maxShorts = inBuf.capacity() / 2
                            val processLength = length.coerceAtMost(maxShorts)
                            
                            inBuf.asShortBuffer().put(audioBuffer, 0, processLength)
                            val byteLen = processLength * 2
                            audioCodec.queueInputBuffer(inIndex, 0, byteLen, timestampUs, 0)
                            
                            // Keep remaining data if buffer was too small
                            if (processLength < length) {
                                val remaining = ShortArray(length - processLength)
                                System.arraycopy(audioBuffer, processLength, remaining, 0, remaining.size)
                                pendingAudio = remaining
                                pendingAudioLength = remaining.size
                            } else {
                                pendingAudio = null
                                pendingAudioLength = 0
                            }
                        }
                    }
                }

                // Process video output
                var outIndex = videoCodec.dequeueOutputBuffer(videoBufferInfo, 0)
                while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            handleVideoFormat(videoCodec)
                        }
                        outIndex >= 0 -> {
                            handleVideoOutput(videoCodec, outIndex, videoBufferInfo)
                        }
                    }
                    outIndex = videoCodec.dequeueOutputBuffer(videoBufferInfo, 0)
                }

                // Process audio output
                var audioOutIndex = audioCodec.dequeueOutputBuffer(audioBufferInfo, 0)
                while (audioOutIndex >= 0 || audioOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    when {
                        audioOutIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // Audio format info received but not needed as config is set during init
                        }
                        audioOutIndex >= 0 -> {
                            handleAudioOutput(audioCodec, audioOutIndex, audioBufferInfo)
                        }
                    }
                    audioOutIndex = audioCodec.dequeueOutputBuffer(audioBufferInfo, 0)
                }

                Thread.yield()
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Video/Audio loop error", e)
                }
            }
        }
    }

    private fun handleAudioOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val server = rtspServer
        if (server == null || !serverStarted) {
            codec.releaseOutputBuffer(index, false)
            return
        }
        val outBuf = codec.getOutputBuffer(index)
        if (outBuf != null && info.size > 0) {
            outBuf.position(info.offset)
            outBuf.limit(info.offset + info.size)
            server.sendAudio(outBuf, info)
        }
        codec.releaseOutputBuffer(index, false)
    }

    private fun handleVideoFormat(codec: MediaCodec) {
        try {
            val outFormat = codec.outputFormat
            val csd0 = outFormat.getByteBuffer("csd-0")
            val csd1 = outFormat.getByteBuffer("csd-1")
            if (csd0 != null && csd1 != null && !serverStarted) {
                rtspServer!!.resizeCache(2000)
                rtspServer!!.setVideoInfo(csd0, csd1, null)
                rtspServer!!.startServer()
                serverStarted = true
                Log.i(TAG, "RTSP server started on port $port (video+audio)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set video info", e)
        }
    }

    private fun handleVideoOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        val server = rtspServer
        if (server == null || !serverStarted) {
            codec.releaseOutputBuffer(index, false)
            return
        }
        val outBuf = codec.getOutputBuffer(index)
        if (outBuf != null && info.size > 0) {
            outBuf.position(info.offset)
            outBuf.limit(info.offset + info.size)
            server.sendVideo(outBuf, info)
        }
        codec.releaseOutputBuffer(index, false)
    }

    override fun stop() {
        Log.d(TAG, "Stopping RTSP server...")
        isRunning = false
        videoThread?.interrupt()
        try { videoThread?.join(2000) } catch (_: InterruptedException) {}

        try {
            serverStarted = false
            rtspServer?.stopServer()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping RTSP server", e)
        }
        cleanup()
        Log.i(TAG, "RTSP server stopped")
    }

    private fun cleanup() {
        try { videoCodec?.stop() } catch (_: Exception) {}
        try { videoCodec?.release() } catch (_: Exception) {}
        videoCodec = null

        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        audioCodec = null

        rtspServer = null
        i420Buffer = null
        pendingFrame = null
        pendingAudio = null
        videoThread = null
    }

    override fun isRunning(): Boolean = isRunning
    override fun getStreamUrl(): String = "rtsp://<ip>:$port"
    override fun getPort(): Int = port
    override fun getProtocol(): String = StreamProtocol.RTSP.displayName

    override fun onNv21Frame(nv21: ByteArray, width: Int, height: Int, timestampUs: Long) {
        if (!isRunning) return
        val copy = framePool[poolIndex]
        poolIndex = (poolIndex + 1) % framePool.size
        System.arraycopy(nv21, 0, copy, 0, nv21.size)
        pendingFrame = copy
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
        Log.i(TAG, "Connection successful")
    }
    override fun onConnectionFailed(reason: String) {
        Log.e(TAG, "Connection failed: $reason")
    }
    override fun onDisconnect() {
        Log.i(TAG, "Disconnected")
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
}