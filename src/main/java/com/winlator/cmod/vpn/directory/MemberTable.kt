package com.winlator.cmod.vpn.directory

import com.winlator.cmod.vpn.core.RoomMember
import com.winlator.cmod.vpn.core.VpnConfig

// Members of a hosted room and the addresses the host handed out. Not thread safe:
// the host session only touches it from its worker thread.
internal class MemberTable(private val hostName: String, private val maxPlayers: Int) {
    class Member(
        val id: String,
        val name: String,
        val address: String,
        val session: ByteArray,
        var lastSeen: Long
    ) {
        var lastCounter = 0L
        // 0 uses the room socket. Set when this guest was given a socket of its own.
        var door = 0
    }

    private val members = LinkedHashMap<String, Member>()

    val playerCount: Int get() = 1 + members.size

    fun find(id: String): Member? = members[id]

    fun findByAddress(address: String): Member? = members.values.firstOrNull { it.address == address }

    fun all(): List<Member> = members.values.toList()

    // Joining again with the same id (a retry) keeps the address but renews the session.
    // Returns null when the room is full.
    fun join(id: String, name: String, session: ByteArray, now: Long): Member? {
        val address = members[id]?.address ?: freeAddress() ?: return null
        if (members[id] == null && playerCount >= maxPlayers) return null
        return Member(id, name, address, session, now).also { members[id] = it }
    }

    // Two players with the same nickname would be indistinguishable in the list. A retry from
    // the same member does not clash with itself.
    fun nameTaken(name: String, id: String): Boolean =
        name.equals(hostName, ignoreCase = true) ||
            members.values.any { it.id != id && it.name.equals(name, ignoreCase = true) }

    fun remove(id: String): Member? = members.remove(id)

    fun expire(now: Long, staleMs: Long): List<Member> {
        val gone = members.values.filter { now - it.lastSeen > staleMs }
        gone.forEach { members.remove(it.id) }
        return gone
    }

    fun snapshot(): List<RoomMember> =
        listOf(RoomMember(hostName, VpnConfig.HOST_ADDRESS, true)) +
            members.values.map { RoomMember(it.name, it.address, false) }

    private fun freeAddress(): String? {
        val used = members.values.map { it.address }.toSet()
        return (2..maxPlayers).map { VpnConfig.guestAddress(it) }.firstOrNull { it !in used }
    }
}
