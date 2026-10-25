package com.example.network

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.URLEncoder

/**
 * CyberWakeClient sends network wake pulses to automatically launch/open
 * the DASMO CYBER CAPTURE Desktop Client on the PC when the Android app is opened.
 *
 * Transmits across:
 *  1. UDP Broadcast to 255.255.255.255:8089 and local subnet broadcast IPs.
 *  2. Direct UDP Unicast to paired / known PC IP addresses.
 *  3. Direct HTTP GET to http://<pc_ip>:8089/wake as a firewall-resilient fallback.
 */
class CyberWakeClient(private val context: Context) {

    companion object {
        const val DESKTOP_WAKE_PORT = 8089
        private const val TAG = "CyberWakeClient"
    }

    fun sendWakeSignal(
        knownPcIps: List<String> = emptyList(),
        serverPort: Int = 8080,
        scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
        onSuccess: (() -> Unit)? = null
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val phoneIp = NetworkUtils.getLocalIpAddress(context)
                val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
                val phoneName = "$manufacturer ${Build.MODEL}"

                val payload = JSONObject().apply {
                    put("cmd", "WAKE_DESKTOP")
                    put("action", "open_window")
                    put("app", "DASMO CYBER CAPTURE")
                    put("version", "1.6.0")
                    put("phone_ip", phoneIp)
                    put("phone_name", phoneName)
                    put("device_name", phoneName)
                    put("port", serverPort)
                    put("timestamp", System.currentTimeMillis())
                }.toString().toByteArray(Charsets.UTF_8)

                // 1. Gather all broadcast and direct targets
                val targets = mutableSetOf<InetAddress>()
                targets.addAll(NetworkUtils.getBroadcastAddresses())

                for (ip in knownPcIps) {
                    if (ip.isNotBlank() && ip != "127.0.0.1" && ip != phoneIp) {
                        try {
                            targets.add(InetAddress.getByName(ip.trim()))
                        } catch (_: Exception) {}
                    }
                }

                // 2. Transmit 3 UDP bursts spaced 120ms apart for Wi-Fi reliability
                var socket: DatagramSocket? = null
                try {
                    socket = DatagramSocket()
                    socket.broadcast = true

                    for (round in 0 until 3) {
                        for (target in targets) {
                            try {
                                val packet = DatagramPacket(payload, payload.size, target, DESKTOP_WAKE_PORT)
                                socket.send(packet)
                            } catch (e: Exception) {
                                Log.d(TAG, "Error sending UDP wake to $target: ${e.message}")
                            }
                        }
                        if (round < 2) delay(120)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "UDP socket failure during wake: ${e.message}")
                } finally {
                    try { socket?.close() } catch (_: Exception) {}
                }

                // 3. Direct HTTP Wake Fallback for all known PC IPs
                for (ip in knownPcIps) {
                    if (ip.isNotBlank() && ip != "127.0.0.1" && ip != phoneIp) {
                        launch(Dispatchers.IO) {
                            try {
                                val encodedName = URLEncoder.encode(phoneName, "UTF-8")
                                val urlStr = "http://${ip.trim()}:$DESKTOP_WAKE_PORT/wake?phone_ip=$phoneIp&phone_name=$encodedName&port=$serverPort"
                                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                                    requestMethod = "GET"
                                    connectTimeout = 1200
                                    readTimeout = 1200
                                }
                                if (conn.responseCode == 200) {
                                    Log.i(TAG, "Direct HTTP wake acknowledged by PC at $ip")
                                }
                                conn.disconnect()
                            } catch (_: Exception) {}
                        }
                    }
                }

                Log.i(TAG, "Sent Desktop Wake Signal from $phoneName ($phoneIp) to ${targets.size} targets")
                onSuccess?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "Exception during wake signal dispatch", e)
            }
        }
    }

    fun sendWakeSignal(
        scope: CoroutineScope,
        serverPort: Int = 8080,
        knownPcIps: List<String> = emptyList(),
        onSuccess: (() -> Unit)? = null
    ) = sendWakeSignal(knownPcIps, serverPort, scope, onSuccess)
}
