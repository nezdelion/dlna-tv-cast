package io.github.nezdelion.dlnatvcast.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address
import java.net.NetworkInterface

/** The Wi-Fi/Ethernet network the phone shares with the TV. */
data class LanInfo(val address: Inet4Address, val interfaceName: String?) {
    val networkInterface: NetworkInterface?
        get() = interfaceName?.let { runCatching { NetworkInterface.getByName(it) }.getOrNull() }
            ?: runCatching { NetworkInterface.getByInetAddress(address) }.getOrNull()
}

class LocalNetwork(context: Context) {
    private val app = context.applicationContext
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)

    /** IPv4 address on an active Wi-Fi or Ethernet network, or null (mobile data only, offline). */
    fun currentLan(): LanInfo? {
        val candidates = buildList {
            connectivity.activeNetwork?.let { add(it) }
            @Suppress("DEPRECATION")
            addAll(connectivity.allNetworks.filterNot { it == connectivity.activeNetwork })
        }
        for (network in candidates) {
            val caps = connectivity.getNetworkCapabilities(network) ?: continue
            val local = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!local) continue
            val link = connectivity.getLinkProperties(network) ?: continue
            val address = link.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?: continue
            return LanInfo(address, link.interfaceName)
        }
        return null
    }

    /** Runs [block] while holding a multicast lock so SSDP replies are not filtered by Wi-Fi. */
    suspend fun <T> withMulticastLock(block: suspend () -> T): T {
        val lock = wifi.createMulticastLock("dlnatvcast-ssdp").apply { setReferenceCounted(false) }
        lock.acquire()
        try {
            return block()
        } finally {
            lock.release()
        }
    }
}
