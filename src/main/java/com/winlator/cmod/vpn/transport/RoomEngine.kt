package com.winlator.cmod.vpn.transport

import com.winlator.cmod.vpn.core.NetworkEnv
import com.winlator.cmod.vpn.core.VpnConfig
import com.winlator.cmod.vpn.core.VpnNative
import com.winlator.cmod.vpn.core.vpnInfo
import com.winlator.cmod.vpn.core.vpnWarn
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

// Kotlin face of the native tunnel engine (one room at a time). The engine can be opened
// before or after the VPN interface exists; it attaches as soon as it has both.
object RoomEngine {
    class Local(
        val keyHex: String,
        val port: Int,
        val mapped: String,
        val nat: String,
        val forwarded: String,
        val slot: Int
    ) {
        // The forwarded port is the most reliable way in, then the address STUN saw, then the LAN
        // addresses (they work between devices on one network).
        val candidates: List<String> = buildList {
            if (forwarded.isNotEmpty()) add(forwarded)
            if (mapped.isNotEmpty() && mapped != forwarded) add(mapped)
            lanAddresses().forEach { add("$it:$port") }
        }
    }

    class PeerState(
        val address: String,
        val up: Boolean,
        val endpoint: String,
        val tx: Long,
        val rx: Long,
        // Round trip through the tunnel, or -1 until measured.
        val rttMs: Int,
        // "lan" when the peer is reached on the local network, "internet" otherwise.
        val path: String
    )

    private class Tunnel(val fd: Int, val isHost: Boolean, val address: String)

    private val lock = Any()
    @Volatile
    private var local: Local? = null
    // True when the native socket is already pinned to the physical network.
    // Protecting it afterwards would replace that pin.
    @Volatile
    private var pinnedToNetwork = false
    // Set while the room interface exists, so a socket opened later can bypass it when pinning fails.
    private var protector: ((Int) -> Boolean)? = null
    private var tunnel: Tunnel? = null
    private var attached = false
    private val addedAt = ConcurrentHashMap<String, Long>()

    // Blocking: it asks STUN servers for the public address, so keep it off the main thread.
    fun open(): Local? {
        if (!VpnNative.isAvailable) return null
        pinnedToNetwork = false
        val gateway = NetworkEnv.gateway()
        val network = NetworkEnv.upstreamNetworkHandle()
        vpnInfo { "Router gateway: ${gateway.ifEmpty { "-" }} network=$network" }
        val text = VpnNative.nativeEngineOpen(gateway, network) ?: return null
        val fields = fieldsOf(text)
        // Remember the pin before any later failure return, so protect() does not overwrite it.
        pinnedToNetwork = fields["bound"] == "1"
        if (pinnedToNetwork) NetworkEnv.rememberUpstream(network)
        val opened = parseLocal(fields) ?: return null
        if (opened.keyHex.length != 64) return null
        synchronized(lock) {
            local = opened
            attached = false
            tryAttach()
        }
        protectCurrent()
        vpnInfo { "Engine open: mapped=${opened.mapped.ifEmpty { "-" }} nat=${opened.nat} forwarded=${opened.forwarded.ifEmpty { "-" }} bound=$pinnedToNetwork probes=${fields["probes"].orEmpty().ifEmpty { "-" }} candidates=${opened.candidates}" }
        return opened
    }

    // Keeps the UDP socket off the room interface when it could not be pinned at open.
    fun protectSocket(protect: (Int) -> Boolean) {
        protector = protect
        protectCurrent()
    }

    fun clearProtector() {
        protector = null
    }

    private fun protectCurrent() {
        if (pinnedToNetwork || !VpnNative.isAvailable) return
        val protect = protector ?: return
        val fd = VpnNative.nativeEngineSocket()
        if (fd >= 0 && !protect(fd)) vpnWarn { "Could not keep the tunnel socket off the VPN" }
    }

    fun attachTunnel(fd: Int, isHost: Boolean, address: String) = synchronized(lock) {
        tunnel = Tunnel(fd, isHost, address)
        tryAttach()
    }

    fun detachTunnel() = synchronized(lock) {
        tunnel = null
        if (attached) VpnNative.nativeEngineDetach()
        attached = false
    }

    // A fresh public port for one guest. The room socket, and everyone already on it, stays put.
    fun openDoor(): Local? {
        if (!VpnNative.isAvailable) return null
        val network = NetworkEnv.upstreamNetworkHandle()
        val text = VpnNative.nativeEngineOpenDoor(NetworkEnv.gateway(), network) ?: return null
        val fields = fieldsOf(text)
        if (fields["bound"] == "1") NetworkEnv.rememberUpstream(network)
        val opened = parseLocal(fields) ?: return null
        vpnInfo { "Door ${opened.slot} open: mapped=${opened.mapped.ifEmpty { "-" }} nat=${opened.nat} forwarded=${opened.forwarded.ifEmpty { "-" }} bound=${fields["bound"] == "1"} candidates=${opened.candidates}" }
        return opened
    }

    fun closeDoor(slot: Int) {
        if (slot == 0 || !VpnNative.isAvailable) return
        VpnNative.nativeEngineCloseDoor(slot)
    }

    // New room socket and public port. Used when the last guest has left, so nobody is on the old one.
    // Keeps the tunnel interface if it is already up.
    fun reopen(): Local? {
        val kept = synchronized(lock) { tunnel }
        close()
        val opened = open() ?: return null
        if (kept != null) attachTunnel(kept.fd, kept.isHost, kept.address)
        return opened
    }

    fun addPeer(keyHex: String, address: String, candidates: List<String>, initiator: Boolean, slot: Int = 0) {
        if (!VpnNative.isAvailable) return
        val added = VpnNative.nativeEngineAddPeer(keyHex, address, candidates.joinToString(","), initiator, slot)
        if (added) addedAt[address] = System.currentTimeMillis()
        vpnInfo { "Peer $address added=$added initiator=$initiator candidates=$candidates" }
    }

    fun removePeer(address: String) {
        addedAt.remove(address)
        if (VpnNative.isAvailable) VpnNative.nativeEngineRemovePeer(address)
    }

    // Explains why a peer is still not connected once it has had time to, based on what we know
    // about our own network. Null while everything is fine or still within the grace period.
    fun hint(isHost: Boolean, peers: Collection<PeerState>): String? {
        val now = System.currentTimeMillis()
        val stalled = peers.any { !it.up && now - (addedAt[it.address] ?: now) > STALL_MS }
        if (!stalled) return null
        val opened = local ?: return null
        return when {
            isHost && opened.forwarded.isEmpty() ->
                "No port could be opened on your router, so players on mobile data may not reach you. " +
                    "Enable UPnP, NAT-PMP or PCP on the router."
            !isHost && opened.nat == "symmetric" ->
                "Your network (usually mobile data) blocks direct connections. It only works if the host's " +
                    "router has an open port. Try Wi-Fi."
            else -> "Still connecting. Both devices need internet access and the host's room must stay open."
        }
    }

    fun status(): List<PeerState> {
        if (!VpnNative.isAvailable) return emptyList()
        val text = VpnNative.nativeEngineStatus().orEmpty()
        return text.lineSequence().mapNotNull { line ->
            val parts = line.split(',')
            if (parts.size != 7) null
            else PeerState(
                parts[0], parts[1] == "up", parts[2],
                parts[3].toLongOrNull() ?: 0, parts[4].toLongOrNull() ?: 0,
                parts[5].toIntOrNull() ?: -1, parts[6]
            )
        }.toList()
    }

    fun close() = synchronized(lock) {
        if (VpnNative.isAvailable) VpnNative.nativeEngineClose()
        addedAt.clear()
        local = null
        pinnedToNetwork = false
        tunnel = null
        attached = false
    }

    private fun fieldsOf(text: String) =
        text.split(';').associate { it.substringBefore('=') to it.substringAfter('=', "") }

    private fun parseLocal(fields: Map<String, String>): Local? {
        val opened = Local(
            keyHex = fields["pub"].orEmpty(),
            port = fields["port"]?.toIntOrNull() ?: return null,
            mapped = fields["mapped"].orEmpty(),
            nat = fields["nat"].orEmpty(),
            forwarded = fields["forwarded"].orEmpty(),
            slot = fields["slot"]?.toIntOrNull() ?: 0
        )
        if (opened.keyHex.length != 64) return null
        return opened
    }

    private fun tryAttach() {
        val ready = tunnel ?: return
        if (local == null || attached) return
        attached = VpnNative.nativeEngineStart(ready.fd, ready.isHost, ready.address)
        vpnInfo { "Engine attached to the tunnel: $attached" }
    }

    private fun lanAddresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !isCellular(it.name) }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }
            .mapNotNull { it.hostAddress }
            .filter { !it.startsWith(VpnConfig.SUBNET_PREFIX) }
            .take(MAX_LAN_CANDIDATES)
    } catch (e: Exception) {
        emptyList()
    }

    // Mobile interfaces carry carrier-internal addresses that mean nothing to other players.
    private fun isCellular(name: String) =
        CELLULAR_PREFIXES.any { name.startsWith(it) || name.startsWith("v4-$it") }

    private val CELLULAR_PREFIXES = listOf("rmnet", "ccmni", "clat", "seth", "pdp", "ppp", "tun")

    private const val MAX_LAN_CANDIDATES = 4
    private const val STALL_MS = 8_000L
}
