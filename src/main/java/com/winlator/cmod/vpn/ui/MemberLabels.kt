package com.winlator.cmod.vpn.ui

import com.winlator.cmod.vpn.core.ActiveRoom
import com.winlator.cmod.vpn.core.RoomMember
import com.winlator.cmod.vpn.transport.RoomEngine

internal fun connectionHint(active: ActiveRoom, peers: Map<String, RoomEngine.PeerState>): String? =
    RoomEngine.hint(active.isHost, peers.values)

// Short form for the sidebar, shown after the nickname: " (host, 23 ms)", " (you)" or "".
internal fun playerDetail(active: ActiveRoom, member: RoomMember, peers: Map<String, RoomEngine.PeerState>): String {
    val peer = peers[member.address]
    val parts = buildList {
        if (member.isHost) add("host")
        when {
            member.address == active.localAddress -> add("you")
            peer == null -> {}
            !peer.up -> add("connecting...")
            peer.rttMs >= 0 -> add("${peer.rttMs} ms")
        }
    }
    return if (parts.isEmpty()) "" else parts.joinToString(", ", " (", ")")
}

// Only the link to the host is observable from a guest; the others go through the host.
internal fun connectionLabel(active: ActiveRoom, member: RoomMember, peers: Map<String, RoomEngine.PeerState>): String {
    if (member.address == active.localAddress) return "you - "
    val peer = peers[member.address]
    if (peer == null) return if (!active.isHost && !member.isHost) "via host - " else ""
    if (!peer.up) return "connecting... - "
    val details = listOfNotNull(
        peer.path.takeIf { it.isNotEmpty() },
        peer.rttMs.takeIf { it >= 0 }?.let { "$it ms" }
    ).joinToString(", ")
    return if (details.isEmpty()) "connected - " else "connected ($details) - "
}
