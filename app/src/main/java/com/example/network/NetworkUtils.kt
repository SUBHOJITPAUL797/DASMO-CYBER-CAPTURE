package com.example.network

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {

    fun getLocalIpAddress(context: Context): String {
        try {
            // First check Wi-Fi manager ip
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.connectionInfo?.ipAddress?.let { ipInt ->
                if (ipInt != 0) {
                    val ip = String.format(
                        "%d.%d.%d.%d",
                        ipInt and 0xff,
                        ipInt shr 8 and 0xff,
                        ipInt shr 16 and 0xff,
                        ipInt shr 24 and 0xff
                    )
                    if (ip != "0.0.0.0" && ip != "127.0.0.1") {
                        return ip
                    }
                }
            }

            // Scan active network interfaces with prioritized matching (wlan, ap/hotspot, rndis, tun)
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            var hotspotIp: String? = null
            var tetherIp: String? = null
            var fallbackIp: String? = null

            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (host.startsWith("127.")) continue

                        if (name.startsWith("wlan")) {
                            return host // Primary Wi-Fi interface
                        } else if (name.startsWith("ap") || name.startsWith("softap") || host.startsWith("192.168.43.")) {
                            hotspotIp = host // Hotspot interface
                        } else if (name.startsWith("rndis") || name.startsWith("usb") || host.startsWith("192.168.42.")) {
                            tetherIp = host // USB / RNDIS tethering
                        } else if (fallbackIp == null) {
                            fallbackIp = host
                        }
                    }
                }
            }

            if (!hotspotIp.isNullOrEmpty()) return hotspotIp
            if (!tetherIp.isNullOrEmpty()) return tetherIp
            if (!fallbackIp.isNullOrEmpty()) return fallbackIp

        } catch (_: Exception) {}
        return "127.0.0.1"
    }

    fun getAllLocalIpAddresses(): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.isLoopback || !intf.isUp) continue
                val name = intf.name.lowercase()
                val label = when {
                    name.startsWith("wlan") -> "Wi-Fi LAN"
                    name.startsWith("ap") || name.startsWith("softap") -> "Hotspot AP"
                    name.startsWith("rndis") || name.startsWith("usb") -> "USB Tether"
                    name.startsWith("tun") || name.startsWith("tap") -> "VPN Tunnel"
                    name.startsWith("eth") -> "Ethernet"
                    else -> "Interface ($name)"
                }

                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val host = addr.hostAddress ?: continue
                        if (!host.startsWith("127.")) {
                            result.add(label to host)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return result
    }

    fun getWifiSsid(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(net)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                val info = wm?.connectionInfo
                val ssid = info?.ssid?.replace("\"", "") ?: "Wi-Fi LAN"
                if (ssid == "<unknown ssid>" || ssid.isEmpty()) "Wi-Fi LAN" else ssid
            } else if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                "Cellular 5G/4G Net"
            } else if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) {
                "Ethernet LAN"
            } else {
                "Wireless Network"
            }
        } catch (_: Exception) {
            "Wi-Fi LAN"
        }
    }

    fun getBatteryInfo(context: Context): Pair<Int, Float> {
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryStatus = context.registerReceiver(null, filter)
            val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val tempTenths = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100
            val tempC = tempTenths / 10.0f
            Pair(pct, if (tempC > 0) tempC else 31.5f)
        } catch (_: Exception) {
            Pair(95, 32.0f)
        }
    }
}
