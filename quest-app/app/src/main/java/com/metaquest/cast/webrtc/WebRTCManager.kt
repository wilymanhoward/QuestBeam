package com.metaquest.cast.webrtc

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.Log
import com.metaquest.cast.model.NetworkMode
import com.metaquest.cast.model.QualityPreset
import com.metaquest.cast.model.StreamMetrics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpSender
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

interface WebRTCListener {
    fun onLocalOfferCreated(sdp: String)
    fun onIceCandidateGenerated(sdpMid: String?, sdpMLineIndex: Int, candidate: String)
    fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState)
    fun onStreamMetricsUpdated(metrics: StreamMetrics)
}

/**
 * Native Meta Quest 3 WebRTC Engine
 */
class WebRTCManager(
    private val context: Context,
    private val listener: WebRTCListener
) {
    private val tag = "WebRTCManager"
    private val eglBase = EglBase.create()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var videoSource: VideoSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var capturer: MediaProjectionCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null

    var isUsbMode: Boolean = false

    private val scope = CoroutineScope(Dispatchers.Main)
    private var statsJob: Job? = null

    init {
        initPeerConnectionFactory()
    }

    private fun initPeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            true, // enableIntelVp8Encoder
            true  // enableH264HighProfile
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options())
            .createPeerConnectionFactory()
    }

    fun startScreenStreaming(
        permissionData: Intent,
        qualityPreset: QualityPreset,
        includeAudio: Boolean,
        iceServers: List<PeerConnection.IceServer> = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
        )
    ) {
        // Clean up previous peer connection and stats polling, but keep active capturer if already running
        statsJob?.cancel()
        statsJob = null
        peerConnection?.close()
        peerConnection = null

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        }

        peerConnection = factory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                listener.onIceCandidateGenerated(candidate.sdpMid, candidate.sdpMLineIndex, candidate.sdp)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                Log.d(tag, "WebRTC PeerConnection state changed: $newState")
                listener.onConnectionStateChanged(newState)
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onTrack(transceiver: org.webrtc.RtpTransceiver?) {}
        })

        // Setup Video Track only once per permission session to avoid Android 14 MediaProjection reuse SecurityException
        if (capturer == null || videoTrack == null) {
            surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            videoSource = factory?.createVideoSource(true)

            capturer = MediaProjectionCapturer(permissionData, object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(tag, "MediaProjection stopped by system")
                }
            })
            capturer?.initialize(surfaceTextureHelper, context, videoSource?.capturerObserver)
            capturer?.startCapture(qualityPreset.width, qualityPreset.height, qualityPreset.fps)

            videoTrack = factory?.createVideoTrack("ARDAMSv0", videoSource)
            videoTrack?.setEnabled(true)
        }

        peerConnection?.addTrack(videoTrack, listOf("ARDAMS"))

        // Setup Audio Track if enabled
        if (includeAudio) {
            if (audioTrack == null) {
                val audioConstraints = MediaConstraints()
                audioSource = factory?.createAudioSource(audioConstraints)
                audioTrack = factory?.createAudioTrack("ARDAMSa0", audioSource)
                audioTrack?.setEnabled(true)
            }
            peerConnection?.addTrack(audioTrack, listOf("ARDAMS"))
        }

        // Create SDP Offer
        createOffer()

        // Start Stats Polling
        startStatsPolling(qualityPreset)
    }

    private fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() {
                        Log.d(tag, "Local SDP Offer successfully set")
                        listener.onLocalOfferCreated(desc.description)
                    }
                    override fun onCreateFailure(err: String?) {}
                    override fun onSetFailure(err: String?) {
                        Log.e(tag, "Failed to set local description: $err")
                    }
                }, desc)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(err: String?) {
                Log.e(tag, "Failed to create offer: $err")
            }
            override fun onSetFailure(err: String?) {}
        }, constraints)
    }

    fun handleRemoteAnswer(sdp: String) {
        val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, sdp)
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.d(tag, "Remote SDP Answer set successfully")
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(err: String?) {
                Log.e(tag, "Failed to set remote answer: $err")
            }
        }, remoteDesc)
    }

    fun addRemoteIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
    }

    private fun startStatsPolling(preset: QualityPreset) {
        statsJob?.cancel()
        statsJob = scope.launch(Dispatchers.IO) {
            var prevBytes: Long = 0
            var prevTime: Long = 0

            while (isActive) {
                delay(1000)
                val pc = peerConnection ?: continue
                if (pc.connectionState() != PeerConnection.PeerConnectionState.CONNECTED) continue

                pc.getStats { report ->
                    var rttMs = 0
                    var currentMode = NetworkMode.IDLE
                    var bitrateMbps = 0f

                    for (stat in report.statsMap.values) {
                        if (stat.type == "candidate-pair") {
                            val localCandidateId = stat.members["localCandidateId"] as? String
                            val remoteCandidateId = stat.members["remoteCandidateId"] as? String
                            val currentRtt = stat.members["currentRoundTripTime"] as? Double

                            if (currentRtt != null) {
                                rttMs = (currentRtt * 1000).toInt()
                            }

                            val local = report.statsMap[localCandidateId]
                            val remote = report.statsMap[remoteCandidateId]
                            val localType = local?.members?.get("candidateType") as? String
                            val remoteType = remote?.members?.get("candidateType") as? String

                            if (isUsbMode) {
                                currentMode = NetworkMode.USB
                            } else if (localType == "host" && remoteType == "host") {
                                currentMode = NetworkMode.LAN
                            } else if (localType == "relay" || remoteType == "relay") {
                                currentMode = NetworkMode.CLOUD
                            }
                        }

                        if (stat.type == "outbound-rtp" && stat.members["kind"] == "video") {
                            val bytesSent = (stat.members["bytesSent"] as? Number)?.toLong() ?: 0L
                            val timestamp = (stat.timestampUs / 1000).toLong()

                            if (prevTime > 0 && timestamp > prevTime) {
                                val deltaBits = (bytesSent - prevBytes) * 8
                                val deltaSec = (timestamp - prevTime) / 1000f
                                if (deltaSec > 0) {
                                    bitrateMbps = (deltaBits / deltaSec) / 1_000_000f
                                }
                            }
                            prevBytes = bytesSent
                            prevTime = timestamp
                        }
                    }

                    val netDescription = when (currentMode) {
                        NetworkMode.USB -> "⚡ Ultra-Fast USB-C Cable (Direct Bus, 0ms)"
                        NetworkMode.LAN -> "Local Wi-Fi P2P"
                        NetworkMode.CLOUD -> "Cloud TURN Relay"
                        else -> "Standby"
                    }

                    listener.onStreamMetricsUpdated(
                        StreamMetrics(
                            fps = preset.fps,
                            bitrateMbps = bitrateMbps,
                            rttMs = rttMs,
                            resolution = "${preset.width}x${preset.height}",
                            networkMode = currentMode,
                            networkDescription = netDescription
                        )
                    )
                }
            }
        }
    }

    fun stopStreaming() {
        statsJob?.cancel()
        statsJob = null

        capturer?.stopCapture()
        capturer?.dispose()
        capturer = null

        videoTrack?.dispose()
        videoTrack = null
        videoSource?.dispose()
        videoSource = null

        audioTrack?.dispose()
        audioTrack = null
        audioSource?.dispose()
        audioSource = null

        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null

        peerConnection?.close()
        peerConnection = null
    }

    fun destroy() {
        stopStreaming()
        factory?.dispose()
        factory = null
        eglBase.release()
    }
}
