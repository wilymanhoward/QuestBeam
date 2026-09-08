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

                val isStreaming by captureService?.isStreaming?.collectAsState(false)
                    ?: remember { mutableStateOf(false) }
                val metrics by captureService?.streamMetrics?.collectAsState(StreamMetrics())
                    ?: remember { mutableStateOf(StreamMetrics()) }

                // Auto-detect on app launch: prioritzes USB Cable (127.0.0.1) then Wi-Fi
                LaunchedEffect(Unit) {
                    isScanningNetwork = true
                    val res = networkDetector.discoverServer()
                    isScanningNetwork = false
                    if (res.url != null) {
                        currentConfig = currentConfig.copy(signalingUrl = res.url)
                        if (!res.activeRoomCode.isNullOrBlank()) {
                            roomCode = res.activeRoomCode
                            currentConfig = currentConfig.copy(roomCode = res.activeRoomCode)
                        }
                        configState = currentConfig
                        val roomInfo = if (!res.activeRoomCode.isNullOrBlank()) " • Room: ${res.activeRoomCode}" else ""
                        val msg = if (res.isUsbActive) {
                            "⚡ USB Cable detected! Zero-delay direct mode active$roomInfo"
                        } else {
                            "Laptop detected via Wi-Fi at ${res.url}$roomInfo"
                        }
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    } else if (res.isUsbCablePlugged) {
                        Toast.makeText(
                            this@MainActivity,
                            "⚡ USB Cable connected! Please tap 'Allow USB debugging' on the headset prompt.",
                            Toast.LENGTH_LONG
                        ).show()
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
                                isScanningNetwork = true
                                val res = networkDetector.discoverServer()
                                isScanningNetwork = false

                                if (res.url != null) {
                                    currentConfig = currentConfig.copy(signalingUrl = res.url)
                                    if (!res.activeRoomCode.isNullOrBlank()) {
                                        roomCode = res.activeRoomCode
                                        currentConfig = currentConfig.copy(roomCode = res.activeRoomCode)
                                    }
                                    configState = currentConfig
                                    val roomInfo = if (!res.activeRoomCode.isNullOrBlank()) " • Synced to Website Room [${res.activeRoomCode}]" else ""
                                    val msg = if (res.isUsbActive) {
                                        "⚡ Connected via USB Cable (0ms)!$roomInfo"
                                    } else {
                                        "Connected via Wi-Fi!$roomInfo"
                                    }
                                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                                } else if (res.isUsbCablePlugged) {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "⚡ USB Cable is connected! Please tap 'Always allow from this computer' -> 'Allow' on the headset prompt.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                } else {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Laptop not found on USB or local Wi-Fi. Ensure signaling server is running on laptop.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
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
