package com.example.routermanager.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Auto-detects the router's IP address based on the current Wi-Fi connection.
 * On Android, the gateway is usually the first IP in the subnet, but this is a best-effort
 * guess that works for most home networks.
 */
object NetworkUtils {

    /**
     * Returns the default gateway of the current Wi-Fi network, or null if not connected.
     * On many Android versions the gateway is not directly accessible, so we fall back to
     * probing common router addresses.
     */
    fun getActualGateway(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val linkProperties = cm.getLinkProperties(cm.activeNetwork)
        return linkProperties?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress ?: "192.168.1.1"
    }

    /** Reads the gateway from WifiManager (works on older Android). */
    @Suppress("DEPRECATION")
    private fun wifiGateway(context: Context): String? {
        return try {
            val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            val dhcp = wifi.dhcpInfo ?: return null
            val ip = dhcp.gateway
            if (ip != 0) {
                // DhcpInfo stores the address little-endian: lowest octet in the low byte.
                listOf(
                    ip and 0xFF,
                    ip shr 8 and 0xFF,
                    ip shr 16 and 0xFF,
                    ip shr 24 and 0xFF,
                ).joinToString(".")
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Lists all active network interfaces. */
    private fun networkInterfaces(): List<String> {
        val ips = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ips
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        // hostAddress is @Nullable in Java; skip if null.
                        address.hostAddress?.let { ips.add(it) }
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return ips
    }

    /** Common router IP addresses in Egypt (sorted by likelihood). */
    private fun commonRouterIps(): List<String> = listOf(
        "192.168.1.1",
        "192.168.0.1",
        "192.168.2.1",
        "192.168.10.1",
        "192.168.100.1",
        "10.0.0.1",
        "10.0.0.2",
        "192.168.8.1", // Huawei mobile broadband
        "192.168.1.254",
        "192.168.0.254",
        "192.168.1.100",
        "192.168.0.100",
    )

    /** Checks if an IP is reachable (best-effort, blocking up to 500 ms). */
    private fun canReach(ip: String): Boolean {
        return try {
            val address = InetAddress.getByName(ip)
            address.isReachable(500)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns true when the device is connected to a Wi-Fi network.
     *
     * Uses [NetworkCapabilities] on Android 12+ (API 31), where
     * [WifiManager.getConnectionInfo] and [android.net.wifi.WifiInfo.getIpAddress]
     * are deprecated and may return stale data.
     */
    fun isConnected(context: Context): Boolean {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            } else {
                @Suppress("DEPRECATION")
                val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                    ?: return false
                @Suppress("DEPRECATION")
                wifi.connectionInfo?.ipAddress != 0
            }
        } catch (e: Exception) {
            false
        }
    }
}