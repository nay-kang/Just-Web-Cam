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
                        val client = serverSocket?.accept()
                        client?.let {
                            handleClient(it)
                        }
                    } catch (e: SocketException) {
                        if (isActive) {
                            Log.w(TAG, "SocketException while accepting client", e)
                        }
                        break
                    } catch (e: IOException) {
                        Log.e(TAG, "IO Exception during client accept", e)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Could not start server socket", e)
            } finally {
                serverSocket?.closeSafely()
                isRunning = false
                Log.i(TAG, "MJPEG stream server stopped")
            }
        }
    }

    override fun stop() {
        Log.d(TAG, "Stopping MJPEG stream server...")
        serverJob?.cancel()
        serverSocket?.closeSafely()
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
                val input = BufferedReader(InputStreamReader(client.getInputStream()))
                val output = client.getOutputStream()

                val requestLine = input.readLine()
                val rawPath = requestLine?.split(" ")?.getOrNull(1) ?: "/"
                val path = rawPath.split("?").firstOrNull() ?: "/"

                Log.d(TAG, "Request path: $path")

                when (path) {
                    "/" -> {
                        val htmlResource = R.raw.stream_player
                        val inputStream = context.resources.openRawResource(htmlResource)
                        val htmlBytes = inputStream.readBytes()
                        inputStream.close()

                        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                        output.write("Content-Type: text/html\r\n\r\n".toByteArray())
                        output.write(htmlBytes)
                        output.flush()
                        Log.d(TAG, "HTML page sent to client: ${client.inetAddress.hostAddress}")
                    }

                    "/video" -> {
                        clientCounter.incrementAndGet()
                        clientCountCallback(clientCounter.get())
                        
                        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                        output.write("Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n".toByteArray())
                        output.flush()
                        Log.d(TAG, "MJPEG stream started for client: ${client.inetAddress.hostAddress}")
                        
                        while (isActive) {
                            try {
                                val frame = frameQueue.take()
                                output.write("--frame\r\n".toByteArray())
                                output.write("Content-Type: image/jpeg\r\n".toByteArray())
                                output.write("Content-Length: ${frame.size}\r\n\r\n".toByteArray())
                                output.flush()
                                output.write(frame)
                                output.flush()
                            } catch (e: InterruptedException) {
                                if (isActive) {
                                    Log.w(TAG, "InterruptedException while taking frame", e)
                                }
                                break
                            } catch (e: IOException) {
                                Log.e(TAG, "IO Exception during frame streaming", e)
                                break
                            }
                        }
                        
                        val currentClientCount = clientCounter.decrementAndGet()
                        clientCountCallback(currentClientCount)
                    }

                    else -> {
                        val notFoundResponse = "<html><body><h1>404 Not Found</h1></body></html>"
                        output.write("HTTP/1.1 404 Not Found\r\n".toByteArray())
                        output.write("Content-Type: text/html\r\n\r\n".toByteArray())
                        output.write(notFoundResponse.toByteArray())
                        output.flush()
                        Log.w(TAG, "404 Not Found sent for path: $path")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client", e)
            } finally {
                client.closeSafely()
                Log.d(TAG, "Client disconnected: ${client.inetAddress.hostAddress}")
            }
        }
    }

    private fun ServerSocket.closeSafely() {
        try {
            this.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing server socket", e)
        }
    }

    private fun Socket.closeSafely() {
        try {
            this.close()
        } catch (e: IOException) {
            Log.e(TAG, "Error closing client socket", e)
        }
    }

    companion object {
        private const val TAG = "MjpegStreamService"
    }
}
