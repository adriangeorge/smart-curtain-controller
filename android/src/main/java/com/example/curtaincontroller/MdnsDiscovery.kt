package com.example.curtaincontroller

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager

class MdnsDiscovery(context: Context) {

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    fun start(onFound: (ip: String) -> Unit) {
        multicastLock = wifi.createMulticastLock("curtain_mdns").apply {
            setReferenceCounted(true)
            acquire()
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}

            override fun onServiceFound(info: NsdServiceInfo) {
                if (!info.serviceName.contains("curtain", ignoreCase = true)) return
                nsdManager.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val ip = info.host?.hostAddress ?: return
                        onFound(ip)
                    }
                })
            }
        }

        nsdManager.discoverServices("_http._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    fun stop() {
        try { discoveryListener?.let { nsdManager.stopServiceDiscovery(it) } } catch (_: Exception) {}
        discoveryListener = null
        multicastLock?.release()
        multicastLock = null
    }
}
