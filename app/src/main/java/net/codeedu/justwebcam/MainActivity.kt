package net.codeedu.justwebcam

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.RadioGroup
import android.widget.RadioButton
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
    private lateinit var autoStartSwitch: Switch
    private lateinit var protocolRadioGroup: RadioGroup
    private lateinit var mjpegRadioButton: RadioButton
    private lateinit var rtspRadioButton: RadioButton
    private lateinit var sharedPreferences: SharedPreferences

    // Use constants for keys
    companion object {
        private const val TAG = "MainActivity"
        private const val TIMESTAMP_STATE_KEY = "timestamp_state"
        private const val AUTO_START_STATE_KEY = "auto_start_state"
        private const val STREAM_PROTOCOL_KEY = "stream_protocol"
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
        autoStartSwitch = findViewById(R.id.autoStartSwitch)
        protocolRadioGroup = findViewById(R.id.protocolRadioGroup)
        mjpegRadioButton = findViewById(R.id.mjpegRadioButton)
        rtspRadioButton = findViewById(R.id.rtspRadioButton)
    }

    private fun initializeSharedPreferences() {
        sharedPreferences = getSharedPreferences(PREF_FILE_KEY, MODE_PRIVATE)
        val savedTimestampState = sharedPreferences.getBoolean(TIMESTAMP_STATE_KEY, true)
        timestampSwitch.isChecked = savedTimestampState

        val savedAutoStartState = sharedPreferences.getBoolean(AUTO_START_STATE_KEY, false)
        autoStartSwitch.isChecked = savedAutoStartState

        val savedProtocol = sharedPreferences.getString(STREAM_PROTOCOL_KEY, StreamProtocol.MJPEG.name)
        val protocol = try {
            StreamProtocol.valueOf(savedProtocol ?: StreamProtocol.MJPEG.name)
        } catch (_: IllegalArgumentException) {
            StreamProtocol.MJPEG
        }
        
        when (protocol) {
            StreamProtocol.MJPEG -> mjpegRadioButton.isChecked = true
            StreamProtocol.RTSP -> rtspRadioButton.isChecked = true
        }
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
        val audioPermissionGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
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
        }
        
        if (audioPermissionGranted) {
            Log.d(TAG, "Audio permission granted")
        } else {
            Log.e(TAG, "Audio permission denied")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (notificationPermissionGranted) {
                Log.d(TAG, "Notification permission granted")
            } else {
                Log.e(TAG, "Notification permission denied")
            }
        }
        updateUIState()
        checkAutoStartStreaming()
    }

    private fun checkAutoStartStreaming() {
        if (autoStartSwitch.isChecked && !isStreaming) {
            val cameraPermissionGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
            val audioPermissionGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (cameraPermissionGranted && audioPermissionGranted) {
                startStreamingService()
            }
        }
    }

    private fun requestPermissionsIfNeeded() {
        val permissionsToRequest = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
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
            checkAutoStartStreaming()
        }
    }

    private fun setupListeners() {
        streamToggleButton.setOnCheckedChangeListener { _, isChecked ->
            handleStreamToggle(isChecked)
        }

        timestampSwitch.setOnCheckedChangeListener { _, isChecked ->
            handleTimestampSwitchChange(isChecked)
        }

        autoStartSwitch.setOnCheckedChangeListener { _, isChecked ->
            handleAutoStartSwitchChange(isChecked)
        }

        protocolRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            handleProtocolChange(checkedId)
        }
    }

    private fun handleAutoStartSwitchChange(isChecked: Boolean) {
        sharedPreferences.edit { putBoolean(AUTO_START_STATE_KEY, isChecked) }
    }

    private fun handleProtocolChange(checkedId: Int) {
        val protocol = when (checkedId) {
            R.id.mjpegRadioButton -> StreamProtocol.MJPEG
            R.id.rtspRadioButton -> StreamProtocol.RTSP
            else -> StreamProtocol.MJPEG
        }
        
        sharedPreferences.edit { putString(STREAM_PROTOCOL_KEY, protocol.name) }
        
        if (isStreaming) {
            sendProtocolChangeToService(protocol)
        }
        
        updateUIState()
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
        val cameraPermissionGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        val audioPermissionGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        
        if (cameraPermissionGranted && audioPermissionGranted) {
            Intent(this, CameraStreamService::class.java).apply {
                action = CameraStreamService.ACTION_START_STREAM
                putExtra(CameraStreamService.EXTRA_SHOW_TIMESTAMP, timestampSwitch.isChecked)
                ContextCompat.startForegroundService(this@MainActivity, this)
            }
            isStreaming = true
            updateUIState()
        } else {
            if (!cameraPermissionGranted) {
                Log.w(TAG, "Camera permission not granted, cannot start streaming")
            }
            if (!audioPermissionGranted) {
                Log.w(TAG, "Audio permission not granted, RTSP streaming may fail")
            }
            multiplePermissionsResultLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
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
        val cameraPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        val audioPermissionGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        
        streamToggleButton.isEnabled = cameraPermissionGranted
        streamToggleButton.isChecked = isStreaming
        
        val ipAddress = getIPv4Address()
        val protocol = when (protocolRadioGroup.checkedRadioButtonId) {
            R.id.mjpegRadioButton -> StreamProtocol.MJPEG
            R.id.rtspRadioButton -> StreamProtocol.RTSP
            else -> StreamProtocol.MJPEG
        }
        
        val addressLabel = when (protocol) {
            StreamProtocol.MJPEG -> getString(R.string.http_address_label, ipAddress)
            StreamProtocol.RTSP -> getString(R.string.rtsp_address_label, ipAddress)
        }
        
        ipAddressTextView.text = addressLabel
    }

    private fun getIPv4Address(): String? {
        val connectivityManager =
            getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
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

    private fun sendProtocolChangeToService(protocol: StreamProtocol) {
        Intent(this, CameraStreamService::class.java).apply {
            action = CameraStreamService.ACTION_CHANGE_PROTOCOL
            putExtra(CameraStreamService.EXTRA_STREAM_PROTOCOL, protocol.name)
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