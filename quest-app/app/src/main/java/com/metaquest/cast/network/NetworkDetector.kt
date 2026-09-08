package com.metaquest.cast.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL

/**
 * Discovers device local IP and detects co-located Signaling Servers on the Wi-Fi network.
 */
class NetworkDetector(private val context: Context) {

    /**
     * Retrieve the active local IPv4 address on the Wi-Fi interface
     */
    fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                if (intf.isLoopback || !intf.isUp) continue

                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "127.0.0.1"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
     * Attempt to auto-discover the Laptop's signaling server on the local subnet
     */
    suspend fun autoDiscoverSignalingServer(port: Int = 8080): String? = withContext(Dispatchers.IO) {
        val localIp = getLocalIpAddress()
        if (localIp == "127.0.0.1") return@withContext null

        val parts = localIp.split(".")
        if (parts.size != 4) return@withContext null
        val prefix = "${parts[0]}.${parts[1]}.${parts[2]}"

        // Quick probe common candidate addresses (e.g. gateway, host, nearby IPs)
        val candidates = mutableListOf<String>()
        getGatewayIp()?.let { candidates.add(it) }
        val myLastOctet = parts[3].toIntOrNull() ?: 100

        // Probe +/- 10 around the headset's own IP
        for (i in (myLastOctet - 10)..(myLastOctet + 10)) {
            if (i in 1..254 && i != myLastOctet) {
                candidates.add("$prefix.$i")
            }
        }

        for (ip in candidates) {
            try {
                val url = URL("http://$ip:$port/health")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 150
                conn.readTimeout = 150
                conn.requestMethod = "GET"
                if (conn.responseCode == 200) {
                    conn.disconnect()
                    return@withContext "ws://$ip:$port"
                }
                conn.disconnect()
            } catch (_: Exception) {
                // Ignore probe timeouts
            }
        }

        return@withContext null
    }
}
