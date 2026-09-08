package com.metaquest.cast.model

/**
 * Quality Presets for Meta Quest 3 Screen Mirroring
 */
enum class QualityPreset(
    val title: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val description: String
) {
    BALANCED_1080P(
        title = "1080p 60 FPS",
        width = 1920,
        height = 1080,
        fps = 60,
        bitrateKbps = 12000,
        description = "Recommended for smooth gameplay mirroring"
    ),
    ULTRA_1440P(
        title = "1440p 60 FPS",
        width = 2560,
        height = 1440,
        fps = 60,
        bitrateKbps = 20000,
        description = "Maximum sharpness for high-bandwidth local Wi-Fi 6"
    ),
    SAVER_720P(
        title = "720p 60 FPS",
        width = 1280,
        height = 720,
        fps = 60,
        bitrateKbps = 6000,
        description = "Optimized for battery saving and slower remote networks"
    )
}

/**
 * Network Routing Topology Mode
 */
enum class NetworkMode(val label: String) {
    IDLE("Disconnected"),
    LAN("Local Network (Direct P2P)"),
    CLOUD("Cloud WebRTC Relay (TURN)")
}

/**
 * Real-time Streaming Performance Metrics
 */
data class StreamMetrics(
    val fps: Int = 0,
    val bitrateMbps: Float = 0.0f,
    val rttMs: Int = 0,
    val resolution: String = "1920x1080",
    val networkMode: NetworkMode = NetworkMode.IDLE,
    val networkDescription: String = "Waiting to connect"
)

/**
 * User Configuration Profile
 */
data class CastConfig(
    val roomCode: String = "Q3-CAST",
    val signalingUrl: String = "ws://192.168.0.56:8080",
    val qualityPreset: QualityPreset = QualityPreset.BALANCED_1080P,
    val captureAudio: Boolean = false,
    val autoDetectLocalServer: Boolean = true
)
