package com.example.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

class CyberNsdBroadcaster(private val context: Context) {
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var isRegistered = false

    fun startBroadcasting(port: Int, deviceName: String = "DASMO-CYBER-CAPTURE") {
        if (isRegistered) return

        try {
            nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = deviceName
                serviceType = "_dasmo-cyber-capture._tcp."
                setPort(port)
                setAttribute("version", "1.0")
                setAttribute("protocol", "mjpeg-pcm-ws")
                setAttribute("video_endpoint", "/video_feed")
                setAttribute("audio_endpoint", "/audio_feed")
                setAttribute("control_endpoint", "/ws")
            }

            registrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo?) {
                    isRegistered = true
                    Log.d("CyberNSD", "Broadcasting DASMO CYBER CAPTURE on port $port")
                }

                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    isRegistered = false
                    Log.w("CyberNSD", "NSD registration failed with code $errorCode")
                }

                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo?) {
                    isRegistered = false
                    Log.d("CyberNSD", "NSD service unregistered")
                }

                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {
                    isRegistered = false
                }
            }

            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e("CyberNSD", "Error registering NSD service", e)
        }
    }

    fun stopBroadcasting() {
        if (!isRegistered || registrationListener == null) return
        try {
            nsdManager?.unregisterService(registrationListener)
            isRegistered = false
        } catch (e: Exception) {
            Log.e("CyberNSD", "Error unregistering NSD service", e)
        }
    }
}
