package com.metaquest.cast.network

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.metaquest.cast.model.NetworkMode
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

interface SignalingListener {
    fun onJoinedRoom(roomCode: String)
    fun onNetworkTopologyDetected(mode: NetworkMode, description: String)
    fun onViewerReady(roomCode: String)
    fun onAnswerReceived(sdp: String)
    fun onIceCandidateReceived(sdpMid: String?, sdpMLineIndex: Int, candidate: String)
    fun onViewerDisconnected()
    fun onScreenshotRequested()
    fun onError(message: String)
}

/**
 * WebSocket Signaling Client communicating with the Signaling Server
 */
class SignalingClient(
    private val signalingUrl: String,
    private val listener: SignalingListener
) {
    private val tag = "SignalingClient"
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var currentRoomCode: String = ""
    private var localIp: String = ""

    fun connect(roomCode: String, localIpAddress: String) {
        currentRoomCode = roomCode
        localIp = localIpAddress

        Log.d(tag, "Connecting to signaling server at $signalingUrl...")
        val request = Request.Builder().url(signalingUrl).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val isUsb = signalingUrl.contains("127.0.0.1") || signalingUrl.contains("localhost")
                Log.d(tag, "WebSocket connected (isUsb=$isUsb). Joining room $currentRoomCode as 'quest'")
                val joinMsg = JsonObject().apply {
                    addProperty("type", "join")
                    addProperty("roomCode", currentRoomCode)
                    addProperty("role", "quest")
                    addProperty("localIp", if (isUsb) "127.0.0.1" else localIp)
                    addProperty("isUsb", isUsb)
                }
                webSocket.send(joinMsg.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = gson.fromJson(text, JsonObject::class.java)
                    val type = msg.get("type")?.asString ?: return

                    when (type) {
                        "joined" -> {
                            val code = msg.get("roomCode")?.asString ?: currentRoomCode
                            listener.onJoinedRoom(code)
                        }
                        "network-topology" -> {
                            val modeStr = msg.get("mode")?.asString ?: "unknown"
                            val desc = msg.get("description")?.asString ?: ""
                            val mode = when (modeStr) {
                                "usb" -> NetworkMode.USB
                                "lan" -> NetworkMode.LAN
                                else -> NetworkMode.CLOUD
                            }
                            listener.onNetworkTopologyDetected(mode, desc)
                        }
                        "viewer-ready" -> {
                            val code = msg.get("roomCode")?.asString ?: currentRoomCode
                            listener.onViewerReady(code)
                        }
                        "answer" -> {
                            val payload = msg.getAsJsonObject("payload")
                            val sdp = payload.get("sdp")?.asString ?: ""
                            listener.onAnswerReceived(sdp)
                        }
                        "ice-candidate" -> {
                            val payload = msg.getAsJsonObject("payload")
                            val candidate = payload.get("candidate")?.asString ?: ""
                            val sdpMid = payload.get("sdpMid")?.asString
                            val sdpMLineIndex = payload.get("sdpMLineIndex")?.asInt ?: 0
                            listener.onIceCandidateReceived(sdpMid, sdpMLineIndex, candidate)
                        }
                        "peer-disconnected" -> {
                            listener.onViewerDisconnected()
                        }
                        "request-screenshot" -> {
                            Log.d(tag, "Received request-screenshot command from viewer")
                            listener.onScreenshotRequested()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(tag, "Error parsing incoming message", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "Signaling connection failure: ${t.message}")
                listener.onError(t.message ?: "Signaling connection failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "Signaling connection closed ($code: $reason)")
            }
        })
    }

    fun sendOffer(sdp: String) {
        val payload = JsonObject().apply {
            addProperty("sdp", sdp)
        }
        val msg = JsonObject().apply {
            addProperty("type", "offer")
            addProperty("roomCode", currentRoomCode)
            addProperty("role", "quest")
            add("payload", payload)
        }
        webSocket?.send(msg.toString())
    }

    fun sendIceCandidate(sdpMid: String?, sdpMLineIndex: Int, candidate: String) {
        val payload = JsonObject().apply {
            addProperty("sdpMid", sdpMid)
            addProperty("sdpMLineIndex", sdpMLineIndex)
            addProperty("candidate", candidate)
        }
        val msg = JsonObject().apply {
            addProperty("type", "ice-candidate")
            addProperty("roomCode", currentRoomCode)
            addProperty("role", "quest")
            add("payload", payload)
        }
        webSocket?.send(msg.toString())
    }

    fun sendScreenshot(dataUrl: String, width: Int, height: Int) {
        val payload = JsonObject().apply {
            addProperty("dataUrl", dataUrl)
            addProperty("width", width)
            addProperty("height", height)
            addProperty("timestamp", System.currentTimeMillis())
        }
        val msg = JsonObject().apply {
            addProperty("type", "screenshot-ready")
            addProperty("roomCode", currentRoomCode)
            addProperty("role", "quest")
            add("payload", payload)
        }
        webSocket?.send(msg.toString())
        Log.i(tag, "Sent HD screenshot payload (${width}x$height) to signaling server")
    }

    fun disconnect() {
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
    }
}
