package com.metaquest.cast

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.metaquest.cast.model.CastConfig
import com.metaquest.cast.model.StreamMetrics
import com.metaquest.cast.network.NetworkDetector
import com.metaquest.cast.service.ScreenCaptureService
import com.metaquest.cast.ui.screens.HomeScreen
import com.metaquest.cast.ui.screens.SettingsScreen
import com.metaquest.cast.ui.theme.QuestCastTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Main Activity for Meta Horizon OS Spatial 2D Panel
 */
class MainActivity : ComponentActivity() {

    private var captureService: ScreenCaptureService? = null
    private var isServiceBound = false

    private var currentConfig = CastConfig()
    private var isScanningNetwork by mutableStateOf(false)
    private var isSettingsOpen by mutableStateOf(false)

    private var pendingProjectionData: Intent? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as ScreenCaptureService.LocalBinder
            captureService = localBinder.getService()
            isServiceBound = true
            pendingProjectionData?.let { data ->
                captureService?.startCasting(data, currentConfig)
                pendingProjectionData = null
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            captureService = null
            isServiceBound = false
        }
    }

    // MediaProjection permission launcher
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)

            if (captureService != null) {
                captureService?.startCasting(result.data!!, currentConfig)
            } else {
                pendingProjectionData = result.data!!
                bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
            Toast.makeText(this, "Screen Mirroring Started!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Bind to ScreenCaptureService
        val serviceIntent = Intent(this, ScreenCaptureService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        val networkDetector = NetworkDetector(this)
        val localIp = networkDetector.getLocalIpAddress()

        setContent {
            QuestCastTheme {
                val coroutineScope = rememberCoroutineScope()
                var roomCode by remember { mutableStateOf(currentConfig.roomCode) }
                var configState by remember { mutableStateOf(currentConfig) }
                var isUsbConnectedState by remember { mutableStateOf(networkDetector.isUsbCablePlugged()) }
                var connectionStatusText by remember { mutableStateOf<String?>(null) }

                val isStreaming by captureService?.isStreaming?.collectAsState(false)
                    ?: remember { mutableStateOf(false) }
                val metrics by captureService?.streamMetrics?.collectAsState(StreamMetrics())
                    ?: remember { mutableStateOf(StreamMetrics()) }

                // Unified auto-discovery function
                suspend fun runDiscovery(isAutoTrigger: Boolean = false) {
                    isScanningNetwork = true
                    val res = networkDetector.discoverServer(cloudFallbackUrl = configState.signalingUrl)
                    isScanningNetwork = false
                    isUsbConnectedState = res.isUsbActive || res.isUsbCablePlugged

                    if (res.url != null) {
                        val previousRoom = roomCode
                        currentConfig = currentConfig.copy(signalingUrl = res.url)
                        if (!res.activeRoomCode.isNullOrBlank()) {
                            roomCode = res.activeRoomCode
                            currentConfig = currentConfig.copy(roomCode = res.activeRoomCode)
                        }
                        configState = currentConfig

                        if (res.isUsbActive) {
                            connectionStatusText = "⚡ USB-C Cable Direct Bus (0ms) • Synced to Room [${res.activeRoomCode ?: roomCode}]"
                            if (!isAutoTrigger || previousRoom != res.activeRoomCode) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "⚡ Auto-paired with website room [${res.activeRoomCode ?: roomCode}] via USB!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else if (res.url.startsWith("wss://") || res.url.contains("trycloudflare")) {
                            connectionStatusText = "☁️ Remote Cloud Relay • Synced to Room [${res.activeRoomCode ?: roomCode}]"
                            if (!isAutoTrigger || previousRoom != res.activeRoomCode) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "☁️ Connected to Cloud Room [${res.activeRoomCode ?: roomCode}]!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        } else {
                            connectionStatusText = "Local Wi-Fi Network • Synced to Room [${res.activeRoomCode ?: roomCode}]"
                            if (!isAutoTrigger || previousRoom != res.activeRoomCode) {
                                Toast.makeText(
                                    this@MainActivity,
                                    "Auto-paired with website room [${res.activeRoomCode ?: roomCode}] via Wi-Fi!",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } else if (res.isUsbCablePlugged) {
                        connectionStatusText = "⚡ USB-C Cable detected. Please allow USB debugging on headset."
                        Toast.makeText(
                            this@MainActivity,
                            "⚡ USB Cable connected! Please tap 'Allow USB debugging' on the headset prompt.",
                            Toast.LENGTH_LONG
                        ).show()
                    } else if (!isAutoTrigger) {
                        connectionStatusText = null
                        Toast.makeText(
                            this@MainActivity,
                            "Laptop not found on USB or local Wi-Fi. Ensure QuestBeam server is running on laptop.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }

                // Dynamic USB Hotplug Listener: automatically runs discovery when USB-C cable is inserted
                DisposableEffect(Unit) {
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            val action = intent?.action
                            if (action == "android.hardware.usb.action.USB_STATE" || action == Intent.ACTION_POWER_CONNECTED) {
                                val isPlugged = networkDetector.isUsbCablePlugged()
                                isUsbConnectedState = isPlugged
                                if (isPlugged) {
                                    coroutineScope.launch {
                                        runDiscovery(isAutoTrigger = true)
                                    }
                                }
                            } else if (action == Intent.ACTION_POWER_DISCONNECTED) {
                                isUsbConnectedState = false
                                connectionStatusText = null
                            }
                        }
                    }
                    val filter = IntentFilter().apply {
                        addAction("android.hardware.usb.action.USB_STATE")
                        addAction(Intent.ACTION_POWER_CONNECTED)
                        addAction(Intent.ACTION_POWER_DISCONNECTED)
                    }
                    registerReceiver(receiver, filter)
                    onDispose {
                        try {
                            unregisterReceiver(receiver)
                        } catch (_: Exception) {}
                    }
                }

                // Standby Auto-Sync: continuously poll every 3 seconds while not streaming,
                // so the moment the laptop opens the room code, the headset automatically adopts it without any typing!
                LaunchedEffect(isStreaming) {
                    while (!isStreaming) {
                        runDiscovery(isAutoTrigger = true)
                        delay(3000)
                    }
                }

                if (isSettingsOpen) {
                    SettingsScreen(
                        config = configState,
                        onConfigChange = { newConfig ->
                            configState = newConfig
                            currentConfig = newConfig
                        },
                        onBackClick = { isSettingsOpen = false }
                    )
                } else {
                    HomeScreen(
                        isStreaming = isStreaming,
                        metrics = metrics,
                        roomCode = roomCode,
                        localIpAddress = localIp,
                        isDetecting = isScanningNetwork,
                        isUsbConnected = isUsbConnectedState,
                        signalingUrl = configState.signalingUrl,
                        connectionStatus = connectionStatusText,
                        onRoomCodeChange = {
                            roomCode = it
                            currentConfig = currentConfig.copy(roomCode = it)
                        },
                        onStartCastClick = {
                            val mediaProjectionManager =
                                getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                            projectionLauncher.launch(mediaProjectionManager.createScreenCaptureIntent())
                        },
                        onStopCastClick = {
                            captureService?.stopCasting()
                            Toast.makeText(this@MainActivity, "Casting stopped", Toast.LENGTH_SHORT).show()
                        },
                        onAutoDetectClick = {
                            coroutineScope.launch {
                                runDiscovery(isAutoTrigger = false)
                            }
                        },
                        onSettingsClick = { isSettingsOpen = true }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
        super.onDestroy()
    }
}
