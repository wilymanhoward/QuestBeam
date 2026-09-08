package com.metaquest.cast.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.metaquest.cast.MainActivity
import com.metaquest.cast.R
import com.metaquest.cast.model.CastConfig
import com.metaquest.cast.model.NetworkMode
import com.metaquest.cast.model.StreamMetrics
import com.metaquest.cast.network.NetworkDetector
import com.metaquest.cast.network.SignalingClient
import com.metaquest.cast.network.SignalingListener
import com.metaquest.cast.webrtc.WebRTCListener
import com.metaquest.cast.webrtc.WebRTCManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.PeerConnection

/**
 * Background Foreground Service managing the Meta Quest 3 screen casting lifecycle.
 * Ensures the screen capture remains persistent across VR games and Horizon OS navigation.
 */
class ScreenCaptureService : Service(), WebRTCListener, SignalingListener {

    private val tag = "ScreenCaptureService"
    private val channelId = "QuestCastChannel"
    private val notificationId = 1001

    private val binder = LocalBinder()

    private var webRTCManager: WebRTCManager? = null
    private var signalingClient: SignalingClient? = null
    private var networkDetector: NetworkDetector? = null

    private var permissionData: Intent? = null
    private var currentConfig = CastConfig()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _streamMetrics = MutableStateFlow(StreamMetrics())
    val streamMetrics: StateFlow<StreamMetrics> = _streamMetrics.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): ScreenCaptureService = this@ScreenCaptureService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        networkDetector = NetworkDetector(this)
    }

    fun startCasting(
        screenCaptureIntent: Intent,
        config: CastConfig
    ) {
        if (webRTCManager == null) {
            webRTCManager = WebRTCManager(this, this)
        }
        this.permissionData = screenCaptureIntent
        this.currentConfig = config

        // Start Foreground Service with MediaProjection Type
        val notification = createNotification("Connecting to room ${config.roomCode}...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(notificationId, notification)
        }

        val localIp = if (config.signalingUrl.contains("127.0.0.1") || config.signalingUrl.contains("localhost")) {
            "127.0.0.1"
        } else {
            networkDetector?.getLocalIpAddress() ?: "127.0.0.1"
        }

        val isUsb = config.signalingUrl.contains("127.0.0.1") || config.signalingUrl.contains("localhost")
        webRTCManager?.isUsbMode = isUsb

        // Connect to Signaling Server
        signalingClient?.disconnect()
        signalingClient = SignalingClient(config.signalingUrl, this)
        signalingClient?.connect(config.roomCode, localIp)

        _isStreaming.value = true
    }

    fun stopCasting() {
        signalingClient?.disconnect()
        signalingClient = null

        webRTCManager?.stopStreaming()
        permissionData = null // Clear token to prevent Android 14 MediaProjection reuse SecurityException
        _isStreaming.value = false
        _streamMetrics.value = StreamMetrics(networkMode = NetworkMode.IDLE)

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // --- Signaling Listener Callbacks ---

    override fun onJoinedRoom(roomCode: String) {
        Log.d(tag, "Successfully joined room $roomCode. Waiting for viewer...")
    }

    override fun onNetworkTopologyDetected(mode: NetworkMode, description: String) {
        Log.d(tag, "Signaling detected topology: $mode ($description)")
        if (mode == NetworkMode.USB) {
            webRTCManager?.isUsbMode = true
        }
        _streamMetrics.value = _streamMetrics.value.copy(
            networkMode = mode,
            networkDescription = description
        )
    }

    override fun onViewerReady(roomCode: String) {
        Log.d(tag, "Viewer detected in room $roomCode. Initializing WebRTC stream...")
        val data = permissionData ?: return
        webRTCManager?.startScreenStreaming(
            permissionData = data,
            qualityPreset = currentConfig.qualityPreset,
            includeAudio = currentConfig.captureAudio
        )
    }

    override fun onAnswerReceived(sdp: String) {
        Log.d(tag, "Passing SDP Answer to WebRTC engine")
        webRTCManager?.handleRemoteAnswer(sdp)
    }

    override fun onIceCandidateReceived(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        webRTCManager?.addRemoteIceCandidate(sdpMid, sdpMLineIndex, candidate)
    }

    override fun onViewerDisconnected() {
        Log.d(tag, "Viewer disconnected")
        _streamMetrics.value = _streamMetrics.value.copy(
            networkMode = NetworkMode.IDLE,
            networkDescription = "Viewer disconnected"
        )
    }

    override fun onError(message: String) {
        Log.e(tag, "Signaling error: $message")
    }

    // --- WebRTC Listener Callbacks ---

    override fun onLocalOfferCreated(sdp: String) {
        Log.d(tag, "Sending WebRTC SDP Offer to signaling server")
        signalingClient?.sendOffer(sdp)
    }

    override fun onIceCandidateGenerated(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        signalingClient?.sendIceCandidate(sdpMid, sdpMLineIndex, candidate)
    }

    override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
        Log.d(tag, "PeerConnection state: $state")
        if (state == PeerConnection.PeerConnectionState.DISCONNECTED ||
            state == PeerConnection.PeerConnectionState.FAILED
        ) {
            _streamMetrics.value = _streamMetrics.value.copy(
                networkMode = NetworkMode.IDLE,
                networkDescription = "Connection dropped"
            )
        }
    }

    override fun onStreamMetricsUpdated(metrics: StreamMetrics) {
        _streamMetrics.value = metrics
    }

    // --- Notification Handling ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(statusText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        webRTCManager?.destroy()
        signalingClient?.disconnect()
        super.onDestroy()
    }
}
