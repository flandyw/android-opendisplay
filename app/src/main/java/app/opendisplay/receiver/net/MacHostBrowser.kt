package app.opendisplay.receiver.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.opendisplay.receiver.protocol.WireProtocol

/**
 * Discovers OpenDisplay Mac hosts that advertise reverse-connect
 * (`_opendisplay-mac._tcp`) when the AP blocks Mac→tablet TCP.
 *
 * On resolve, invokes [onHost] with the Mac IPv4 and reverse port so
 * [ReceiverServer.connectOutbound] can dial.
 */
class MacHostBrowser(
    context: Context,
    private val onHost: (host: String, port: Int, name: String) -> Unit,
    /** A Mac stopped advertising (quit, or left the network). */
    private val onLost: (name: String) -> Unit = {},
) {
    private val tag = "MacHostBrowser"
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    @Volatile
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val resolving = HashSet<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var restart: Runnable? = null
    private val found = HashSet<String>()

    @Volatile
    var running = false
        private set

    @Synchronized
    fun start() {
        if (running) return
        running = true
        beginDiscovery()
    }

    private fun beginDiscovery() {
        acquireMulticastLock()
        val listener = object : NsdManager.DiscoveryListener {
            private fun isCurrent() = running && discovery === this
            override fun onDiscoveryStarted(regType: String) {
                Log.i(tag, "discovering $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (!isCurrent()) return
                val type = service.serviceType ?: return
                if (!type.contains("opendisplay-mac", ignoreCase = true)) return
                val key = "${service.serviceName}|$type"
                synchronized(resolving) {
                    found.add(key)
                    if (!resolving.add(key)) return
                }
                Log.i(tag, "found Mac host candidate \"${service.serviceName}\" type=$type")
                try {
                    nsd.resolveService(
                        service,
                        object : NsdManager.ResolveListener {
                            private var retried = false
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                if (!isCurrent()) return
                                Log.w(tag, "resolve failed $errorCode for ${serviceInfo.serviceName}")
                                synchronized(resolving) { resolving.remove(key) }
                                // Retry once after a short pause (OEM mDNS flakes).
                                if (retried) return
                                retried = true
                                handler.postDelayed({
                                    if (!isCurrent()) return@postDelayed
                                    synchronized(resolving) {
                                        if (key !in found || !resolving.add(key)) return@postDelayed
                                    }
                                    try {
                                        nsd.resolveService(serviceInfo, this)
                                    } catch (e: Exception) {
                                        synchronized(resolving) { resolving.remove(key) }
                                        Log.w(tag, "resolve retry threw: ${e.message}")
                                    }
                                }, 1500)
                            }

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                if (!isCurrent()) return
                                synchronized(resolving) {
                                    resolving.remove(key)
                                    if (key !in found) return
                                }
                                val port = if (info.port > 0) info.port else WireProtocol.MAC_REVERSE_PORT
                                val host = pickIPv4(info)
                                    ?: txtIp(info)
                                    ?: info.host?.hostAddress
                                if (host.isNullOrBlank()) {
                                    Log.w(tag, "resolved without host")
                                    return
                                }
                                // Skip IPv6 link-local for dial (often needs scope id).
                                if (host.contains(':') && !host.contains('.')) {
                                    Log.w(tag, "skip IPv6-only host $host")
                                    return
                                }
                                Log.i(tag, "resolved Mac \"${info.serviceName}\" → $host:$port")
                                onHost(host, port, info.serviceName ?: "Mac")
                            }
                        },
                    )
                } catch (e: Exception) {
                    Log.w(tag, "resolve threw: ${e.message}")
                    synchronized(resolving) { resolving.remove(key) }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                if (!isCurrent()) return
                synchronized(resolving) {
                    found.remove("${service.serviceName}|${service.serviceType}")
                }
                Log.i(tag, "lost ${service.serviceName}")
                service.serviceName?.let(onLost)
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i(tag, "discovery stopped")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "start discovery failed: $errorCode — restarting in 3s")
                retryDiscovery(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(tag, "stop discovery failed: $errorCode")
            }
        }
        discovery = listener
        try {
            nsd.discoverServices(WireProtocol.MAC_HOST_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Log.e(tag, "discoverServices threw", e)
            retryDiscovery(listener)
        }
    }

    @Synchronized
    private fun retryDiscovery(failed: NsdManager.DiscoveryListener) {
        if (!running || discovery !== failed) return
        discovery = null
        // Android removes a failed discovery request itself. Do not stop an
        // unregistered listener; release our state and create a fresh request.
        synchronized(resolving) { resolving.clear(); found.clear() }
        releaseMulticastLock()
        restart?.let(handler::removeCallbacks)
        restart = Runnable {
            synchronized(this) {
                restart = null
                if (running && discovery == null) beginDiscovery()
            }
        }.also { handler.postDelayed(it, 3000) }
    }

    @Synchronized
    fun stop() {
        running = false
        restart?.let(handler::removeCallbacks)
        restart = null
        discovery?.let {
            try {
                nsd.stopServiceDiscovery(it)
            } catch (_: Exception) {
            }
        }
        discovery = null
        synchronized(resolving) { resolving.clear(); found.clear() }
        releaseMulticastLock()
    }

    private fun pickIPv4(info: NsdServiceInfo): String? {
        // API 34 returns a List, not an Array. The deprecated host property
        // can return IPv6 even when a usable IPv4 address is in this list.
        val addresses = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses
        } else {
            listOfNotNull(info.host)
        }
        return MacHostAddress.pickIPv4(addresses)
    }

    private fun txtIp(info: NsdServiceInfo): String? {
        return try {
            val map = info.attributes ?: return null
            val raw = map["ip"] ?: return null
            String(raw, Charsets.UTF_8).trim().ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun acquireMulticastLock() {
        if (multicastLock?.isHeld == true) return
        try {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val lock = wifi.createMulticastLock("opendisplay-mac-host")
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        } catch (e: Exception) {
            Log.w(tag, "multicast lock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }
}
