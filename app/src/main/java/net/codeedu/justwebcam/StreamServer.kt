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

class StreamServer(
    private val frameQueue: LinkedBlockingQueue<ByteArray>,
    private val clientCountCallback: (Int) -> Unit, // Callback function
    private val context: Context, // Add Context as a parameter
    private val port: Int
) {
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null // Job to manage the server coroutine
    private val serverScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob()) // Scope for server coroutines
    private val clientCounter = AtomicInteger(0) // Thread-safe client counter

    fun start() {
        if (serverJob?.isActive == true) {
            Log.d(TAG, "Stream server already started") // Avoid starting multiple times
            return
        }
        serverJob = serverScope.launch { // Use serverScope for coroutine
            try {
                serverSocket = ServerSocket(port)
                Log.i(TAG, "Stream server started on port $port")
                while (isActive) { // Check if the coroutine is still active
                    try {
                        val client = serverSocket?.accept()
                        client?.let {
                            handleClient(it)
                        }
                    } catch (e: SocketException) {
                        if (isActive) { // Only log if the exception wasn't due to server stop
                            Log.w(
                                TAG,
                                "SocketException while accepting client (server might be stopping)",
                                e
                            )
                        }
                        break // Break out of the loop if server socket is closed
                    } catch (e: IOException) {
                        Log.e(TAG, "IO Exception during client accept", e)
                    }
                }
            } catch (e: IOException) {
                Log.e(TAG, "Could not start server socket", e)
            } finally {
                serverSocket?.closeSafely() // Ensure server socket is closed in finally block
                Log.i(TAG, "Stream server stopped")
            }
        }
    }

    private fun handleClient(client: Socket) {
        serverScope.launch(Dispatchers.IO) { // Use serverScope for client handling coroutine
            val clientSocket = client // Create local variable for safe closing
            try {
                val input = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
                val output = clientSocket.getOutputStream()

                // Read the request line to get the path
                val requestLine = input.readLine()
                val path = requestLine?.split(" ")?.getOrNull(1) ?: "/" // Default to root path

                Log.d(TAG, "Request path: $path")

                when (path) {
                    "/" -> {
                        // Serve HTML page from resource for root path
                        val htmlResource = R.raw.stream_player // Reference to your HTML file
                        val inputStream = context.resources.openRawResource(htmlResource)
                        val htmlBytes = inputStream.readBytes()
                        inputStream.close()

                        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                        output.write("Content-Type: text/html\r\n\r\n".toByteArray())
                        output.write(htmlBytes) // Send HTML from resource
                        output.flush()
                        Log.d(
                            TAG,
                            "HTML page from resource sent to client: ${clientSocket.inetAddress.hostAddress}"
                        )
                    }

                    "/video" -> {
                        clientCounter.incrementAndGet()
                        clientCountCallback(1) // ensure every time request camera successful
                        // Serve MJPEG stream for /mjpeg_stream path
                        output.write("HTTP/1.1 200 OK\r\n".toByteArray())
                        output.write("Content-Type: multipart/x-mixed-replace; boundary=frame\r\n\r\n".toByteArray())
                        output.flush()
                        Log.d(
                            TAG,
                            "MJPEG stream started for client: ${clientSocket.inetAddress.hostAddress}"
                        )
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
                                    Log.w(
                                        TAG,
                                        "InterruptedException while taking frame (client might be disconnecting)",
                                        e
                                    )
                                }
                                break
                            } catch (e: IOException) {
                                Log.e(TAG, "IO Exception during frame streaming to client", e)
                                break
                            }
                        }
                        val currentClientCount =
                            clientCounter.decrementAndGet() // Decrement client count on disconnect
                        clientCountCallback(currentClientCount) // Notify CameraStreamService
                    }

                    else -> {
                        // Handle unknown paths - send 404 Not Found
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
                clientSocket.closeSafely() // Ensure client socket is closed in finally block
                Log.d(TAG, "Client disconnected: ${clientSocket.inetAddress.hostAddress}")
            }
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping stream server...")
        serverJob?.cancel() // Cancel the server coroutine
        serverSocket?.closeSafely() // Close server socket
        serverSocket = null
        serverJob = null
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
        private const val TAG = "StreamServer" // Correct TAG definition in StreamServer
    }
}