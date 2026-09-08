package com.metaquest.cast.network

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

data class DiscoveryResult(
    val url: String?,
    val activeRoomCode: String? = null,
    val isUsbCablePlugged: Boolean = false,
    val isUsbActive: Boolean = false
)

/**
 * Discovers device local IP and detects co-located Signaling Servers on USB cable and Wi-Fi network.
 * Automatically synchronizes with the active website Room Code.
 * Uses a 3-tier discovery pipeline:
 * 1. High-speed USB Cable loopback check (127.0.0.1:port via ADB reverse tunnel)
 * 2. Instant UDP Broadcast Discovery (5ms ping to 255.255.255.255:8081)
 * 3. Parallel chunked subnet HTTP probing across /24
 */
class NetworkDetector(private val context: Context) {

    private val tag = "NetworkDetector"

    /**
     * Check if USB cable is physically connected to the headset
     */
    fun isUsbCablePlugged(): Boolean {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            plugged == BatteryManager.BATTERY_PLUGGED_USB
        } catch (e: Exception) {
            Log.w(tag, "Could not check USB state: ${e.message}")
            false
        }
    }

    /**
     * Retrieve the active local IPv4 address on the primary Wi-Fi interface (wlan0)
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            val candidateAddrs = mutableListOf<Pair<String, String>>()

            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue

                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        candidateAddrs.add(intf.name to host)
                    }
                }
            }

            // 1. Prefer wlan0 / Wi-Fi interfaces
            val wifiAddr = candidateAddrs.firstOrNull { it.first.startsWith("wlan") }?.second
            if (wifiAddr != null) {
                Log.d(tag, "Discovered Wi-Fi address on wlan: $wifiAddr")
                return wifiAddr
            }

            // 2. Otherwise find an IP in standard private subnets (192.168.x.x, 10.x.x.x, 172.16-31.x.x)
            val privateAddr = candidateAddrs.firstOrNull { (_, ip) ->
                ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")
            }?.second
            if (privateAddr != null) {
                Log.d(tag, "Discovered private IP address: $privateAddr")
                return privateAddr
            }

            return candidateAddrs.firstOrNull()?.second ?: "127.0.0.1"
        } catch (e: Exception) {
            Log.e(tag, "Error reading network interfaces", e)
        }
        return "127.0.0.1"
    }

    /**
     * Get the default gateway IP on the current Wi-Fi network
     */
    fun getGatewayIp(): String? {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        val linkProps = connectivityManager.getLinkProperties(activeNetwork) ?: return null

        for (route in linkProps.routes) {
            if (route.isDefaultRoute && route.gateway is Inet4Address) {
                return route.gateway?.hostAddress
            }
        }
        return null
    }

    /**
     * Probe an individual IP for an active QuestBeam signaling server via HTTP /health.
     * Returns Pair(wsUrl, activeRoomCode) if active, or null.
     */
    private fun probeHost(ip: String, port: Int, timeoutMs: Int = 900): Pair<String, String?>? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://$ip:$port/health")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                requestMethod = "GET"
                instanceFollowRedirects = false
            }
            if (conn.responseCode == 200) {
                Log.i(tag, "Successfully reached QuestBeam signaling server at $ip:$port")
                val responseBody = try {
                    conn.inputStream.bufferedReader().readText()
                } catch (_: Exception) { "" }

                val roomRegex = """"activeRoomCode"\s*:\s*"([^"]+)"""".toRegex()
                val activeRoom = roomRegex.find(responseBody)?.groupValues?.get(1)
                if (!activeRoom.isNullOrBlank()) {
                    Log.i(tag, "Discovered active website room code: $activeRoom")
                }
                Pair("ws://$ip:$port", activeRoom)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Instant UDP broadcast discovery. Broadcasts to 255.255.255.255:8081 and awaits response.
     * Returns Pair(wsUrl, activeRoomCode) in < 15ms.
     */
    private fun discoverViaUdp(beaconPort: Int = 8081): Pair<String, String?>? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket().apply {
                broadcast = true
                soTimeout = 1200
            }
            val requestData = "QUESTBEAM_DISCOVER".toByteArray()
            val broadcastAddr = InetAddress.getByName("255.255.255.255")
            val packet = DatagramPacket(requestData, requestData.size, broadcastAddr, beaconPort)
            socket.send(packet)
            Log.d(tag, "Sent UDP discovery broadcast to 255.255.255.255:$beaconPort")

            val buffer = ByteArray(1024)
            val responsePacket = DatagramPacket(buffer, buffer.size)
            socket.receive(responsePacket)

            val jsonText = String(responsePacket.data, 0, responsePacket.length)
            Log.i(tag, "Received UDP discovery response: $jsonText from ${responsePacket.address.hostAddress}")

            val wsUrlRegex = """"wsUrl"\s*:\s*"([^"]+)"""".toRegex()
            val match = wsUrlRegex.find(jsonText)
            val url = if (match != null) {
                match.groupValues[1]
            } else {
                val portRegex = """"port"\s*:\s*(\d+)""".toRegex()
                val portMatch = portRegex.find(jsonText)
                val port = portMatch?.groupValues?.get(1)?.toIntOrNull() ?: 8080
                "ws://${responsePacket.address.hostAddress}:$port"
            }

            val roomRegex = """"activeRoomCode"\s*:\s*"([^"]+)"""".toRegex()
            val activeRoom = roomRegex.find(jsonText)?.groupValues?.get(1)
            Log.i(tag, "Discovered laptop via UDP: $url (Website Room: $activeRoom)")
            Pair(url, activeRoom)
        } catch (e: Exception) {
            Log.d(tag, "UDP discovery probe timed out or failed: ${e.message}")
            null
        } finally {
            socket?.close()
        }
    }

    /**
     * Comprehensive multi-tier discovery with automatic Room Code sync:
     * 1. USB Reverse Tunnel (127.0.0.1:8080)
     * 2. UDP LAN Broadcast Beacon
     * 3. Subnet HTTP Parallel Sweep
     */
    suspend fun discoverServer(port: Int = 8080): DiscoveryResult = withContext(Dispatchers.IO) {
        val usbPlugged = isUsbCablePlugged()
        Log.d(tag, "Beginning server discovery (USB Cable Physically Plugged: $usbPlugged)...")

        // 1. FAST PATH: Check if USB Cable / ADB Reverse Tunnel is active (127.0.0.1:port)
        val usbProbe = probeHost("127.0.0.1", port, timeoutMs = 400)
        if (usbProbe != null) {
            Log.i(tag, "⚡ USB Cable Direct connection active on 127.0.0.1:$port (Room: ${usbProbe.second})! Zero-delay mode ready.")
            return@withContext DiscoveryResult(
                url = usbProbe.first,
                activeRoomCode = usbProbe.second,
                isUsbCablePlugged = true,
                isUsbActive = true
            )
        }

        // 2. FAST PATH 2: Instant UDP Broadcast Discovery on Wi-Fi
        val udpDiscovered = discoverViaUdp(beaconPort = 8081)
        if (udpDiscovered != null) {
            Log.i(tag, "Wi-Fi discovery successful via UDP beacon: ${udpDiscovered.first} (Room: ${udpDiscovered.second})")
            return@withContext DiscoveryResult(
                url = udpDiscovered.first,
                activeRoomCode = udpDiscovered.second,
                isUsbCablePlugged = usbPlugged,
                isUsbActive = false
            )
        }

        // 3. FALLBACK: Wi-Fi Subnet HTTP Probing
        val localIp = getLocalIpAddress()
        if (localIp == "127.0.0.1") {
            Log.w(tag, "No Wi-Fi address found and USB reverse tunnel inactive.")
            return@withContext DiscoveryResult(
                url = null,
                activeRoomCode = null,
                isUsbCablePlugged = usbPlugged,
                isUsbActive = false
            )
        }

        val parts = localIp.split(".")
        if (parts.size != 4) {
            return@withContext DiscoveryResult(null, null, usbPlugged, false)
        }
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
        val myLastOctet = parts[3].toIntOrNull() ?: -1

        Log.d(tag, "Starting subnet HTTP probe on $prefix.0/24...")
        val prioritizedList = mutableListOf<String>()
        val gateway = getGatewayIp()
        if (gateway != null && gateway.startsWith(prefix)) {
            prioritizedList.add(gateway)
        }

        for (i in 1..254) {
            val candidate = "$prefix.$i"
            if (i != myLastOctet && candidate != gateway) {
                prioritizedList.add(candidate)
            }
        }

        val discoveredResult = AtomicReference<Pair<String, String?>?>(null)

        // Probe in parallel batches of 25 to avoid saturating Android's socket pool
        val chunks = prioritizedList.chunked(25)
        coroutineScope {
            for (chunk in chunks) {
                if (discoveredResult.get() != null) break
                val jobs = chunk.map { ip ->
                    async {
                        if (discoveredResult.get() != null) return@async
                        val result = probeHost(ip, port, timeoutMs = 700)
                        if (result != null) {
                            discoveredResult.compareAndSet(null, result)
                        }
                    }
                }
                jobs.awaitAll()
            }
        }

        val finalRes = discoveredResult.get()
        return@withContext DiscoveryResult(
            url = finalRes?.first,
            activeRoomCode = finalRes?.second,
            isUsbCablePlugged = usbPlugged,
            isUsbActive = false
        )
    }

    /**
     * Backward-compatible helper for legacy callers
     */
    suspend fun autoDiscoverSignalingServer(port: Int = 8080): String? {
        return discoverServer(port).url
    }
}
