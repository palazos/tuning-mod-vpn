package com.winlator.cmod.vpn.core

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.winlator.cmod.vpn.directory.RoomDirectoryProvider
import com.winlator.cmod.vpn.service.RoomVpnLauncher

// Main-thread only: written by the UI, the directory and the VPN service callbacks.
object RoomSession {
    var current: ActiveRoom? by mutableStateOf(null)
        private set

    var members: List<RoomMember> by mutableStateOf(emptyList())
        private set

    // Why the last room ended without the user asking for it.
    var notice: String? by mutableStateOf(null)
        private set

    private var appContext: Context? = null

    fun activate(room: ActiveRoom, context: Context) {
        appContext = context.applicationContext
        notice = null
        current = room
    }

    fun updateMembers(list: List<RoomMember>) {
        members = list
    }

    // Ends the room because of a failure and takes the tunnel down with it.
    fun end(reason: String) {
        notice = reason
        appContext?.let { RoomVpnLauncher.stop(it) }
        clear()
    }

    fun clear() {
        current?.let {
            val directory = RoomDirectoryProvider.instance
            if (it.isHost) directory.removeRoom(it.room.id) else directory.leaveRoom(it.room.id)
        }
        current = null
        members = emptyList()
    }
}
