package com.metaquest.cast.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/**
 * Discovers device local IP and detects co-located Signaling Servers on the local Wi-Fi network.
 * Uses high-speed parallel coroutine probing across the full /24 subnet.
 */
class NetworkDetector(private val context: Context) {

    private val tag = "NetworkDetector"

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
     * Probe an individual IP for an active QuestBeam signaling server
     */
    private fun probeHost(ip: String, port: Int): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL("http://$ip:$port/health")
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 400
                readTimeout = 400
                requestMethod = "GET"
                instanceFollowRedirects = false
            }
            if (conn.responseCode == 200) {
                Log.i(tag, "Successfully located QuestBeam signaling server at $ip:$port")
                "ws://$ip:$port"
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
     * High-speed parallel auto-discovery across the entire /24 local subnet.
     * Scans all 254 addresses in parallel with early-exit on first match.
     */
    suspend fun autoDiscoverSignalingServer(port: Int = 8080): String? = withContext(Dispatchers.IO) {
        val localIp = getLocalIpAddress()
        if (localIp == "127.0.0.1") {
            Log.w(tag, "Cannot auto-discover: local IP is loopback 127.0.0.1")
            return@withContext null
        }

        val parts = localIp.split(".")
        if (parts.size != 4) return@withContext null
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"
        val myLastOctet = parts[3].toIntOrNull() ?: -1

        Log.d(tag, "Starting parallel subnet discovery on $prefix.0/24 (My IP: $localIp)...")

        // Build list of all 254 host addresses on the subnet, prioritizing gateway and common laptop ranges
        val prioritizedList = mutableListOf<String>()
        val gateway = getGatewayIp()
        if (gateway != null && gateway.startsWith(prefix)) {
            prioritizedList.add(gateway)
        }

        // Add 1..254 (skipping self and gateway)
        for (i in 1..254) {
            val candidate = "$prefix.$i"
            if (i != myLastOctet && candidate != gateway) {
                prioritizedList.add(candidate)
            }
        }

        val discoveredResult = AtomicReference<String?>(null)

        // Probe in parallel batches across the subnet
        coroutineScope {
            val jobs = prioritizedList.map { ip ->
                async {
                    if (discoveredResult.get() != null) return@async
                    val result = probeHost(ip, port)
                    if (result != null) {
                        discoveredResult.compareAndSet(null, result)
                    }
                }
            }
            jobs.awaitAll()
        }

        val finalUrl = discoveredResult.get()
        if (finalUrl != null) {
            Log.i(tag, "Auto-discovery complete: Found $finalUrl")
        } else {
            Log.w(tag, "Auto-discovery complete: No signaling server found on $prefix.0/24")
        }
        return@withContext finalUrl
    }
}
