package net.codeedu.justwebcam

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Switch
import android.widget.TextView
import android.widget.ToggleButton
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface
import androidx.core.content.edit

class MainActivity : ComponentActivity() {

    private lateinit var multiplePermissionsResultLauncher: ActivityResultLauncher<Array<String>>
    private var isStreaming = false // Track streaming state
    private lateinit var streamToggleButton: ToggleButton
    private lateinit var ipAddressTextView: TextView
    private lateinit var timestampSwitch: Switch
    private lateinit var sharedPreferences: SharedPreferences

    // Use constants for keys
    companion object {
        private const val TAG = "MainActivity"
        private const val TIMESTAMP_STATE_KEY = "timestamp_state"
        private const val PREF_FILE_KEY = "main_activity_prefs"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        initializeSharedPreferences()
        initializePermissionLauncher()
        requestPermissionsIfNeeded()
        setupListeners()
        updateUIState()
    }

    private fun initializeViews() {
        streamToggleButton = findViewById(R.id.streamSwitchButton)
        ipAddressTextView = findViewById(R.id.ipAddressTextView)
        timestampSwitch = findViewById(R.id.timestampSwitch)
    }

    private fun initializeSharedPreferences() {
        sharedPreferences = getSharedPreferences(PREF_FILE_KEY, Context.MODE_PRIVATE)
        val savedTimestampState = sharedPreferences.getBoolean(TIMESTAMP_STATE_KEY, true)
        timestampSwitch.isChecked = savedTimestampState
    }

    private fun initializePermissionLauncher() {
        multiplePermissionsResultLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            handlePermissionResult(permissions)
        }
    }

    private fun handlePermissionResult(permissions: Map<String, Boolean>) {
        val cameraPermissionGranted = permissions[Manifest.permission.CAMERA] ?: false
        val notificationPermissionGranted =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
            } else {
                true
            }

        if (cameraPermissionGranted) {
            Log.d(TAG, "Camera permission granted")
        } else {
            Log.e(TAG, "Camera permission denied")
            // Provide user feedback about why camera permission is needed.
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (notificationPermissionGranted) {
                Log.d(TAG, "Notification permission granted")
            } else {
                Log.e(TAG, "Notification permission denied")
                // Provide user feedback about why notification permission is needed.
            }
        }
        updateUIState()
    }

    private fun requestPermissionsIfNeeded() {
        val permissionsToRequest = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsAlreadyGranted = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!permissionsAlreadyGranted) {
            multiplePermissionsResultLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            Log.d(TAG, "Camera and Notification permissions already granted")
        }
    }

    private fun setupListeners() {
        streamToggleButton.setOnCheckedChangeListener { _, isChecked ->
            handleStreamToggle(isChecked)
        }

        timestampSwitch.setOnCheckedChangeListener { _, isChecked ->
            handleTimestampSwitchChange(isChecked)
        }
    }

    private fun handleStreamToggle(isChecked: Boolean) {
        if (isChecked) {
            startStreamingService()
        } else {
            stopStreamingService()
        }
    }

    private fun handleTimestampSwitchChange(isChecked: Boolean) {
        sharedPreferences.edit { putBoolean(TIMESTAMP_STATE_KEY, isChecked) }
        if (isStreaming) {
            sendTimestampSwitchStateToService(isChecked)
        }
    }

    private fun startStreamingService() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            Intent(this, CameraStreamService::class.java).apply {
                action = CameraStreamService.ACTION_START_STREAM
                putExtra(CameraStreamService.EXTRA_SHOW_TIMESTAMP, timestampSwitch.isChecked)
                ContextCompat.startForegroundService(this@MainActivity, this)
            }
            isStreaming = true
            updateUIState()
        } else {
            Log.w(TAG, "Camera permission not granted, cannot start streaming")
            multiplePermissionsResultLauncher.launch(arrayOf(Manifest.permission.CAMERA))
        }
    }

    private fun stopStreamingService() {
        Intent(this, CameraStreamService::class.java).apply {
            action = CameraStreamService.ACTION_STOP_STREAM
            startService(this)
        }
        isStreaming = false
        updateUIState()
    }

    private fun updateUIState() {
        streamToggleButton.isEnabled = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        streamToggleButton.isChecked = isStreaming
        ipAddressTextView.text = getString(R.string.http_address_label, getIPv4Address())
    }

    private fun getIPv4Address(): String? {
        val connectivityManager =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return null
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return null

        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        return NetworkInterface.getNetworkInterfaces()?.toList()?.firstOrNull {
            it.isUp && it.name == "wlan0"
        }?.inetAddresses?.toList()?.firstOrNull {
            !it.isLoopbackAddress && it is Inet4Address
        }?.hostAddress
    }

    private fun sendTimestampSwitchStateToService(showTimestamp: Boolean) {
        Intent(this, CameraStreamService::class.java).apply {
            action = CameraStreamService.ACTION_UPDATE_TIMESTAMP_STATE
            putExtra(CameraStreamService.EXTRA_SHOW_TIMESTAMP, showTimestamp)
            startService(this)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isStreaming) {
            stopStreamingService()
        }
    }
}