package com.winlator.cmod.vpn.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import com.winlator.cmod.vpn.core.RoomSession
import com.winlator.cmod.vpn.core.VpnConfig
import com.winlator.cmod.vpn.core.vpnInfo
import com.winlator.cmod.vpn.core.vpnWarn
import com.winlator.cmod.vpn.transport.RoomEngine

class RoomVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var address: String? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopSelf()
        } else {
            startTunnel(
                intent?.getStringExtra(EXTRA_ADDRESS) ?: VpnConfig.HOST_ADDRESS,
                intent?.getBooleanExtra(EXTRA_HOST, true) ?: true
            )
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }

    private fun startTunnel(localAddress: String, isHost: Boolean) {
        // The socket is created before this interface exists. If it is not already pinned to
        // the physical network, exclude it now so its packets are not captured by the room.
        RoomEngine.protectSocket { protect(it) }
        if (tun != null) {
            if (address == localAddress) return
            closeTunnel()
        }

        // Only the room subnet is routed; regular traffic keeps using the underlying network.
        val descriptor = Builder()
            .setSession("Winlator Rooms")
            .addAddress(localAddress, PREFIX)
            .addRoute(SUBNET, PREFIX)
            .setMtu(MTU)
            .also { addGameDiscoveryRoutes(it) }
            .establish()

        if (descriptor == null) {
            vpnWarn { "VPN permission was not granted" }
            RoomSession.clear()
            return
        }

        tun = descriptor
        address = localAddress
        // Again, now that the interface exists: the first call is a no-op when the engine was not open yet.
        RoomEngine.protectSocket { protect(it) }
        // The engine reads and writes the interface itself; it picks the descriptor up as soon as it is ready.
        RoomEngine.attachTunnel(descriptor.fd, isHost, localAddress)
        holdWifiLock()
        vpnInfo { "Tunnel up: $localAddress/$PREFIX" }
    }

    // With sparse traffic the Wi-Fi radio sleeps between beacons, which adds ~100-200 ms to a round trip.
    @Suppress("DEPRECATION")
    private fun holdWifiLock() {
        if (wifiLock?.isHeld == true) return
        try {
            val manager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = manager.createWifiLock(mode, "rooms").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: RuntimeException) {
            vpnWarn { "Wi-Fi lock unavailable: ${e.message}" }
        }
    }

    private fun releaseWifiLock() {
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
        } catch (e: RuntimeException) {
            vpnWarn { "Wi-Fi lock release failed: ${e.message}" }
        }
        wifiLock = null
    }

    // Games look for each other with the limited broadcast and multicast, which the system would
    // otherwise send out through the normal network instead of the room.
    private fun addGameDiscoveryRoutes(builder: Builder) {
        for ((destination, prefix) in DISCOVERY_ROUTES) {
            try {
                builder.addRoute(destination, prefix)
            } catch (e: IllegalArgumentException) {
                vpnWarn { "Route $destination/$prefix rejected: ${e.message}" }
            }
        }
    }

    private fun stopTunnel() {
        closeTunnel()
        RoomEngine.clearProtector()
        RoomSession.clear()
    }

    private fun closeTunnel() {
        // The engine holds its own copy of the descriptor, so it must let go first.
        RoomEngine.detachTunnel()
        releaseWifiLock()
        tun?.close()
        tun = null
        address = null
        vpnInfo { "Tunnel down" }
    }

    companion object {
        const val ACTION_STOP = "com.winlator.cmod.vpn.STOP"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_HOST = "host"

        private const val SUBNET = "10.77.0.0"
        private const val PREFIX = 24
        private const val MTU = 1280
        private val DISCOVERY_ROUTES = listOf("255.255.255.255" to 32, "224.0.0.0" to 4)
    }
}
