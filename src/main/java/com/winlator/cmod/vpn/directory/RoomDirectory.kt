package com.winlator.cmod.vpn.directory

import com.winlator.cmod.vpn.core.RoomInfo

enum class DirectoryState { IDLE, CONNECTING, ONLINE, OFFLINE }

sealed interface JoinResult {
    data class Joined(val address: String) : JoinResult

    // [nicknameProblem]: the host refused the nickname (taken or not allowed), so the player
    // can pick another and try again. [codeProblem]: the join code of a private room is
    // missing or wrong (or the room is temporarily locked after too many wrong codes).
    data class Rejected(
        val reason: String,
        val nicknameProblem: Boolean = false,
        val codeProblem: Boolean = false
    ) : JoinResult
}

class CreatedRoom(val info: RoomInfo, val joinCode: String)

interface RoomDirectory {
    // Both are backed by snapshot state so Compose recomposes when they change.
    val rooms: List<RoomInfo>
    val state: DirectoryState

    // Start and stop listening for rooms; a hosted or joined room is unaffected.
    fun start()

    fun stop()

    // Returns null when the room cannot be created (for example, no native engine).
    // The join code is generated here; a private room also requires it to enter.
    fun createRoom(name: String, game: String, maxPlayers: Int, hostName: String, private: Boolean, hidden: Boolean): CreatedRoom?

    fun removeRoom(id: String)

    // Host only: removes the guest at [address] from the hosted room.
    fun kickMember(address: String)

    // Asks the host of [room] for a slot. The result is delivered on the main thread.
    // From then on the member list is published through RoomSession.
    fun joinRoom(room: RoomInfo, nickname: String, code: String?, onResult: (JoinResult) -> Unit)

    fun leaveRoom(id: String)

    // Host only. Invoked on the main thread once the public port is known, or with false if hosting failed.
    // Registering after the port is already known runs the action immediately.
    fun whenHostReady(action: (Boolean) -> Unit)
}

object RoomDirectoryProvider {
    val instance: RoomDirectory by lazy { MqttRoomDirectory() }
}
