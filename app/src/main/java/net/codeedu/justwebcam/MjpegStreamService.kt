package net.codeedu.justwebcam

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * MJPEG streaming service implementation
 * Serves MJPEG streams over HTTP with a web interface
 */
class MjpegStreamService(
    private val frameQueue: LinkedBlockingQueue<ByteArray>,
    private val clientCountCallback: (Int) -> Unit,
    private val context: Context,
    private val port: Int
) : StreamService {
    
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val clientCounter = AtomicInteger(0)
    private var isRunning = false

    override fun start() {
        if (serverJob?.isActive == true) {
            Log.d(TAG, "MJPEG stream server already started")
            return
        }
        
        serverJob = serverScope.launch {
            try {
                serverSocket = ServerSocket(port)
                isRunning = true
                Log.i(TAG, "MJPEG stream server started on port $port")
                
                while (isActive) {
                    try {
                        serverSocket?.accept()?.let { handleClient(it) }
                    } catch (e: SocketException) {
                        if (isActive) Log.w(TAG, "SocketException while accepting client", e)
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception during client accept", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Could not start server socket", e)
            } finally {
                serverSocket?.safeClose()
                isRunning = false
                Log.i(TAG, "MJPEG stream server stopped")
            }
        }
    }

    override fun stop() {
        Log.d(TAG, "Stopping MJPEG stream server...")
        serverJob?.cancel()
        serverSocket?.safeClose()
        serverSocket = null
        serverJob = null
        isRunning = false
    }

    override fun isRunning(): Boolean = isRunning
    override fun getStreamUrl(): String = "http://localhost:$port/video"
    override fun getPort(): Int = port
    override fun getProtocol(): String = StreamProtocol.MJPEG.displayName

    private fun handleClient(client: Socket) {
        serverScope.launch(Dispatchers.IO) {
            try {
                client.tcpNoDelay = true
                BufferedReader(InputStreamReader(client.getInputStream())).use { input ->
                    client.getOutputStream().use { output ->
                        val requestLine = input.readLine()
                        val rawPath = requestLine?.split(" ")?.getOrNull(1) ?: "/"
                        val path = rawPath.split("?").firstOrNull() ?: "/"

                        Log.d(TAG, "Request path: $path")

                        when (path) {
                            "/" -> serveHtml(output)
                            "/video" -> serveVideo(client, output)
                            else -> serveNotFound(output, path)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client", e)
            } finally {
                client.safeClose()
                Log.d(TAG, "Client disconnected: ${client.inetAddress.hostAddress}")
            }
        }
    }

    private fun serveHtml(output: java.io.OutputStream) {
        runCatching {
            context.resources.openRawResource(R.raw.stream_player).use { inputStream ->
                val htmlBytes = inputStream.readBytes()
                output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                output.write("Content-Type: text/html\r\n\r\n".toByteArray())
                output.write(htmlBytes)
                output.flush()
                Log.d(TAG, "HTML page sent")
            }
        }.onFailure { e ->
            Log.e(TAG, "Error serving HTML", e)
        }
    }

    private fun serveVideo(client: Socket, output: java.io.OutputStream) {
        clientCountCallback(clientCounter.incrementAndGet())
        
        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
        output.write("Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n".toByteArray())
        output.flush()
        Log.d(TAG, "MJPEG stream started for client: ${client.inetAddress.hostAddress}")
        
        while (isRunning) {
            try {
                val frame = frameQueue.take()
                output.write("--frame\r\n".toByteArray())
                output.write("Content-Type: image/jpeg\r\n".toByteArray())
                output.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray())
                output.flush()
                output.write(frame)
                output.flush()
            } catch (e: InterruptedException) {
                Log.w(TAG, "Interrupted while taking frame", e)
                break
            } catch (e: IOException) {
                Log.e(TAG, "IO Exception during frame streaming", e)
                break
            }
        }

        clientCountCallback(clientCounter.decrementAndGet())
    }

    private fun serveNotFound(output: java.io.OutputStream, path: String) {
        val notFoundResponse = "<html><body><h1>404 Not Found</h1></body></html>"
        output.write("HTTP/1.1 404 Not Found\r\n".toByteArray())
        output.write("Content-Type: text/html\r\n\r\n".toByteArray())
        output.write(notFoundResponse.toByteArray())
        output.flush()
        Log.w(TAG, "404 Not Found sent for path: $path")
    }

    private fun ServerSocket.safeClose() = runCatching { close() }.onFailure { 
        Log.e(TAG, "Error closing server socket", it) 
    }

    private fun Socket.safeClose() = runCatching { close() }.onFailure { 
        Log.e(TAG, "Error closing client socket", it) 
    }

    companion object {
        private const val TAG = "MjpegStreamService"
    }
}