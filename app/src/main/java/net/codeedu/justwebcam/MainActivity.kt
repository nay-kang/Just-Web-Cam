package net.codeedu.justwebcam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.AppCompatToggleButton
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : ComponentActivity() {

    private lateinit var multiplePermissionsResultLauncher: ActivityResultLauncher<Array<String>>
    private var isStreaming = false // Track streaming state
    private lateinit var streamSwitchButton: AppCompatToggleButton // Switch button
    private lateinit var ipAddressTextView: TextView // TextView for IP address

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main) // Ensure you have activity_main.xml layout

        streamSwitchButton = findViewById(R.id.streamSwitchButton) // Switch button findViewById
        ipAddressTextView = findViewById(R.id.ipAddressTextView) // IP address TextView findViewById

        multiplePermissionsResultLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val cameraPermissionGranted = permissions[Manifest.permission.CAMERA] ?: false
            val notificationPermissionGranted =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
                } else {
                    true // On older versions, POST_NOTIFICATIONS is not needed
                }

            if (cameraPermissionGranted) {
                Log.d(TAG, "Camera permission granted")
            } else {
                Log.e(TAG, "Camera permission denied")
                // Inform user, disable streaming features if camera permission is essential
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (notificationPermissionGranted) {
                    Log.d(TAG, "Notification permission granted")
                } else {
                    Log.e(TAG, "Notification permission denied")
                    // Inform user notifications might be disabled
                }
            }
            updateUIState() // Update UI based on permissions
        }

        /* Request camera and notification permissions together at startup */
        val permissionsToRequest = mutableListOf<String>(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsAlreadyGranted = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (permissionsAlreadyGranted) {
            Log.d(TAG, "Both Camera and Notification permissions already granted at startup")
        } else {
            multiplePermissionsResultLauncher.launch(permissionsToRequest.toTypedArray())
        }

        streamSwitchButton.setOnCheckedChangeListener { _, isChecked -> // Switch listener
            if (isChecked) {
                startStreamingService()
            } else {
                stopStreamingService()
            }
        }

        updateUIState() // Initial UI state
    }

    private fun startStreamingService() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            Intent(this, CameraStreamService::class.java).also { intent ->
                intent.action = CameraStreamService.ACTION_START_STREAM // Use action to start
                ContextCompat.startForegroundService(this, intent) // Start as foreground service
            }
            isStreaming = true
            updateUIState()
        } else {
            Log.w(TAG, "Camera permission not granted, cannot start streaming")
            // Optionally, request permission again or inform user
            multiplePermissionsResultLauncher.launch(arrayOf(Manifest.permission.CAMERA)) // Re-request if needed (only camera this time for start attempt)
        }
    }

    private fun stopStreamingService() {
        Intent(this, CameraStreamService::class.java).also { intent ->
            intent.action = CameraStreamService.ACTION_STOP_STREAM // Use action to stop
            startService(intent) // No need for foreground context to stop
        }
        isStreaming = false
        updateUIState()
    }

    private fun updateUIState() {
        streamSwitchButton.isEnabled =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED

        streamSwitchButton.isChecked = isStreaming // Set switch state

        ipAddressTextView.text = getString(R.string.http_address_label,getIPv4Address()) // Update IP address
    }

    private fun getIPv4Address(): String? {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities =
            connectivityManager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        NetworkInterface.getNetworkInterfaces()?.let { interfaces ->
            Collections.list(interfaces).forEach { iface ->
                if (iface.isUp && iface.name == "wlan0") { // "wlan0" is typical Wi-Fi interface
                    Collections.list(iface.inetAddresses).forEach { addr ->
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress
                        }
                    }
                }
            }
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isStreaming) {
            stopStreamingService() // Ensure service is stopped when Activity is destroyed (optional, depends on desired behavior)
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}