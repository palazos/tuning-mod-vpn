package com.winlator.cmod.vpn.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

// Facts about the device's physical network that the native engine cannot learn on its own.
object NetworkEnv {
    @Volatile
    private var context: Context? = null

    // The handle that actually accepted a socket. After the room VPN is up, the first
    // upstream network Android lists is not always that one.
    @Volatile
    private var pinnedHandle = 0L

    fun rememberUpstream(handle: Long) {
        if (handle != 0L) pinnedHandle = handle
    }

    fun attach(context: Context) {
        this.context = context.applicationContext
    }

    // The room's TUN captures multicast, so routers must be asked directly at their address.
    // Only Wi-Fi and Ethernet are considered: a mobile network has no router we could configure.
    @Suppress("DEPRECATION")
    fun gateway(): String {
        val manager = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return ""
        return try {
            for (network in manager.allNetworks) {
                val capabilities = manager.getNetworkCapabilities(network) ?: continue
                val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                if (!local || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) continue
                val route = manager.getLinkProperties(network)?.routes
                    ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                val address = (route?.gateway as? Inet4Address)?.hostAddress
                if (!address.isNullOrEmpty()) return address
            }
            ""
        } catch (e: Exception) {
            ""
        }
    }

    // Handle for the physical network the tunnel socket must use. Zero when it cannot be told.
    // Passed to the native engine so a VPN left up from the previous room is not the path STUN
    // and the handshake take.
    fun upstreamNetworkHandle(): Long {
        val manager = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return 0L
        return try {
            val cached = pinnedHandle
            if (cached != 0L && manager.allNetworks.any { network ->
                    network.networkHandle == cached && stillPhysical(manager.getNetworkCapabilities(network))
                }
            ) {
                return cached
            }
            val active = manager.activeNetwork
            if (active != null && isUpstream(manager.getNetworkCapabilities(active))) {
                return active.networkHandle
            }
            manager.allNetworks
                .firstOrNull { isUpstream(manager.getNetworkCapabilities(it)) }
                ?.networkHandle ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    // The cached network only has to still be the physical one. Requiring INTERNET here
    // drops the Wi-Fi that already worked once the room VPN is the active network.
    private fun stillPhysical(capabilities: NetworkCapabilities?): Boolean {
        if (capabilities == null) return false
        val physical = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return physical && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    }

    private fun isUpstream(capabilities: NetworkCapabilities?): Boolean {
        if (capabilities == null) return false
        val physical = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return physical &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
