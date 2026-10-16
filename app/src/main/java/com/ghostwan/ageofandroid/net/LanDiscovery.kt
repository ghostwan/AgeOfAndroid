package com.ghostwan.ageofandroid.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface

/** A game found on the local network. */
data class FoundGame(val name: String, val host: String, val port: Int)

/**
 * Finds and advertises games on the local network with Android's network service discovery
 * (mDNS). Players can still type the host's IP address when a network blocks multicast.
 * Callbacks are delivered on the main thread.
 */
class LanDiscovery(context: Context) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val main = Handler(Looper.getMainLooper())

    private var registration: NsdManager.RegistrationListener? = null
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // Older Android versions resolve one service at a time.
    private val toResolve = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private val found = LinkedHashMap<String, FoundGame>()

    fun advertise(gameName: String, port: Int) {
        stopAdvertising()
        val info = NsdServiceInfo().apply {
            serviceName = gameName.take(60)
            serviceType = SERVICE_TYPE
            setPort(port)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
                Log.w(TAG, "register failed $error")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
        }
        registration = listener
        try {
            nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "register", e)
        }
    }

    fun stopAdvertising() {
        registration?.let {
            try {
                nsd.unregisterService(it)
            } catch (_: Exception) {
            }
        }
        registration = null
    }

    /** Starts looking for games; [onChange] gets the full list each time it changes. */
    fun discover(onChange: (List<FoundGame>) -> Unit) {
        stopDiscovery()
        found.clear()
        multicastLock = wifi.createMulticastLock("ageofandroid").apply {
            setReferenceCounted(false)
            acquire()
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) {
                Log.w(TAG, "discovery failed $error")
            }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                main.post {
                    toResolve += info
                    resolveNext(onChange)
                }
            }

            override fun onServiceLost(info: NsdServiceInfo) {
                main.post {
                    if (found.remove(info.serviceName) != null) onChange(found.values.toList())
                }
            }
        }
        discovery = listener
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.w(TAG, "discover", e)
        }
    }

    @Suppress("DEPRECATION") // resolveService is the only API available before Android 14.
    private fun resolveNext(onChange: (List<FoundGame>) -> Unit) {
        if (resolving || discovery == null) return
        val info = toResolve.removeFirstOrNull() ?: return
        resolving = true
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                    main.post {
                        resolving = false
                        resolveNext(onChange)
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        val address = info.host?.hostAddress
                        if (address != null && discovery != null) {
                            found[info.serviceName] = FoundGame(info.serviceName, address, info.port)
                            onChange(found.values.toList())
                        }
                        resolveNext(onChange)
                    }
                }
            },
        )
    }

    fun stopDiscovery() {
        discovery?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (_: Exception) {
            }
        }
        discovery = null
        toResolve.clear()
        multicastLock?.release()
        multicastLock = null
    }

    fun stop() {
        stopAdvertising()
        stopDiscovery()
    }

    companion object {
        private const val TAG = "LanDiscovery"
        const val SERVICE_TYPE = "_ageofandroid._tcp."

        /** This device's IPv4 addresses on the local network, to show to the other players. */
        fun localAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .map { it.hostAddress ?: "" }
                .filter { it.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
