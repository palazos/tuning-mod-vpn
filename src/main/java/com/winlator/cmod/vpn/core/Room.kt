package com.winlator.cmod.vpn.core

data class RoomInfo(
    val id: String,
    val name: String,
    val game: String,
    val hostName: String,
    val players: Int,
    val maxPlayers: Int,
    // Base64 Ed25519 key of the host, pinned when joining.
    val hostKey: String,
    // Joining needs an access code; the code itself is never announced.
    val isPrivate: Boolean = false,
    // Lets a join code find this room; see JoinCode. Null for rooms announced without one.
    val joinTag: String? = null,
    // Private room kept out of the lists; it is only reachable with its join code.
    val isHidden: Boolean = false
) {
    val isFull: Boolean get() = players >= maxPlayers
}

data class ActiveRoom(
    val room: RoomInfo,
    val isHost: Boolean,
    val localAddress: String,
    // The join code; only the host keeps it, so it can be shared.
    val code: String? = null
)

data class RoomMember(
    val name: String,
    val address: String,
    val isHost: Boolean
)
