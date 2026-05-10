package net.codeedu.justwebcam

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import com.pedro.common.ConnectChecker
import com.pedro.rtspserver.server.RtspServer

class RtspStreamService(
    private val context: Context,
    private val port: Int = 1935
) : StreamService, FrameCallback, ConnectChecker {

    private var rtspServer: RtspServer? = null
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private var serverStarted = false
    private var videoThread: Thread? = null
    private var audioThread: Thread? = null

    @Volatile
    private var pendingFrame: ByteArray? = null
    private var i420Buffer: ByteArray? = null
    private val framePool = Array(2) { ByteArray(CameraStreamService.VIDEO_WIDTH * CameraStreamService.VIDEO_HEIGHT * 3 / 2) }
    private var poolIndex = 0

    companion object {
        private const val TAG = "RtspStreamService"
        private const val VIDEO_MIME = "video/avc"
        private const val AUDIO_MIME = "audio/mp4a-latm"
        private const val VIDEO_BITRATE = 2_000_000
        private const val AUDIO_BITRATE = 64_000
        private const val SAMPLE_RATE = 32000
        private const val CHANNEL_COUNT = 1
        private const val I_FRAME_INTERVAL = 1
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

            startAudio()

            isRunning = true
            serverStarted = false

            videoThread = Thread(::videoLoop, "RtspVideo")
            videoThread!!.start()

            Log.i(TAG, "RTSP video+audio encoder started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start RTSP", e)
            stopAudio()
            cleanup()
            isRunning = false
        }
    }

    private fun startAudio() {
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf.coerceAtLeast(2048)
            )

            val audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, CHANNEL_COUNT)
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            audioFormat.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBuf.coerceAtLeast(4096))

            audioCodec = MediaCodec.createEncoderByType(AUDIO_MIME)
            audioCodec!!.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            audioCodec!!.start()

            audioRecord!!.startRecording()

            audioThread = Thread(::audioLoop, "RtspAudio")
            audioThread!!.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio", e)
            stopAudio()
        }
    }

    private fun audioLoop() {
        val record = audioRecord ?: return
        val codec = audioCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()
        val pcmSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)
        val pcmBuffer = ShortArray(pcmSize / 2)
        var presentationTimeUs = System.nanoTime() / 1000

        while (isRunning) {
            try {
                val read = record.read(pcmBuffer, 0, pcmBuffer.size)
                if (read <= 0) continue

                val inIndex = codec.dequeueInputBuffer(10000)
                if (inIndex >= 0) {
                    val inBuf = codec.getInputBuffer(inIndex)
                    if (inBuf != null) {
                        inBuf.clear()
                        inBuf.order(java.nio.ByteOrder.nativeOrder())
                        inBuf.asShortBuffer().put(pcmBuffer, 0, read)
                        val byteLen = read * 2
                        codec.queueInputBuffer(inIndex, 0, byteLen, presentationTimeUs, 0)
                        presentationTimeUs += read * 1_000_000L / SAMPLE_RATE
                    }
                }

                while (true) {
                    val outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    when {
                        outIndex >= 0 -> {
                            if (serverStarted && bufferInfo.size > 0) {
                                val outBuf = codec.getOutputBuffer(outIndex)
                                if (outBuf != null) {
                                    outBuf.position(bufferInfo.offset)
                                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                    rtspServer?.sendAudio(outBuf, bufferInfo)
                                }
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                        }
                        else -> break
                    }
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Audio loop error", e)
                }
            }
        }
    }

    private fun videoLoop() {
        val codec = videoCodec ?: return
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning) {
            try {
                val frame = pendingFrame
                if (frame != null) {
                    pendingFrame = null
                    val inIndex = codec.dequeueInputBuffer(5000)
                    if (inIndex >= 0) {
                        val inBuf = codec.getInputBuffer(inIndex)
                        if (inBuf != null) {
                            val i420 = getI420Buffer(CameraStreamService.VIDEO_WIDTH, CameraStreamService.VIDEO_HEIGHT)
                            nv21ToI420(frame, i420, CameraStreamService.VIDEO_WIDTH, CameraStreamService.VIDEO_HEIGHT)
                            inBuf.clear()
                            inBuf.put(i420)
                            codec.queueInputBuffer(
                                inIndex, 0, i420.size,
                                System.nanoTime() / 1000, 0
                            )
                        }
                    }
                }

                var outIndex = codec.dequeueOutputBuffer(bufferInfo, 5000)
                while (outIndex >= 0 || outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            handleVideoFormat(codec)
                        }
                        outIndex >= 0 -> {
                            handleVideoOutput(codec, outIndex, bufferInfo)
                        }
                    }
                    outIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (isRunning) {
                    Log.w(TAG, "Video loop error", e)
                }
            }
        }
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
        audioThread?.interrupt()
        try { videoThread?.join(2000) } catch (_: InterruptedException) {}
        try { audioThread?.join(2000) } catch (_: InterruptedException) {}

        try {
            serverStarted = false
            rtspServer?.stopServer()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping RTSP server", e)
        }
        stopAudio()
        cleanup()
        Log.i(TAG, "RTSP server stopped")
    }

    private fun stopAudio() {
        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        audioCodec = null
    }

    private fun cleanup() {
        try { videoCodec?.stop() } catch (_: Exception) {}
        try { videoCodec?.release() } catch (_: Exception) {}
        videoCodec = null
        rtspServer = null
        i420Buffer = null
        pendingFrame = null
        videoThread = null
        audioThread = null
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
