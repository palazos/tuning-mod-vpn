package com.winlator.cmod.vpn.directory

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.winlator.cmod.vpn.core.BrokerConnection
import com.winlator.cmod.vpn.core.vpnWarn
import com.winlator.cmod.vpn.core.JoinCode
import com.winlator.cmod.vpn.core.RoomInfo
import com.winlator.cmod.vpn.core.RoomMember
import com.winlator.cmod.vpn.core.RoomSession
import com.winlator.cmod.vpn.core.TextFilter
import com.winlator.cmod.vpn.core.VpnConfig
import com.winlator.cmod.vpn.core.VpnNative
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttMessage
import java.util.UUID
import java.util.concurrent.Executors

// Rooms are retained messages under TOPIC_ROOT/<roomId>. This class listens for them and
// owns the hosted ([HostSession]) and joined ([GuestSession]) room, if any.
class MqttRoomDirectory : RoomDirectory {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "vpn-directory").apply { isDaemon = true }
    }

    // Main thread only.
    private val roomList = mutableStateListOf<RoomInfo>()
    private val lastSeen = HashMap<String, Long>()
    private var stateValue by mutableStateOf(DirectoryState.IDLE)
    private var listener: MqttAsyncClient? = null
    private var hosting: HostSession? = null
    private var guest: GuestSession? = null
    // Null while the host port is still being learned, then the result of that attempt.
    private var hostReady: Boolean? = null
    private var hostWait: ((Boolean) -> Unit)? = null

    override val rooms: List<RoomInfo> get() = roomList
    override val state: DirectoryState get() = stateValue

    private val sweepTask = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            val hostedId = hosting?.info?.id
            roomList.removeAll { it.id != hostedId && now - (lastSeen[it.id] ?: 0L) > VpnConfig.STALE_MS }
            main.postDelayed(this, SWEEP_MS)
        }
    }

    override fun start() {
        if (listener != null) return
        stateValue = DirectoryState.CONNECTING

        val client = BrokerConnection.newClient("wl-" + shortId())
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                runCatching { client.subscribe("${VpnConfig.TOPIC_ROOT}/+", 0) }
                main.post { stateValue = DirectoryState.ONLINE }
            }

            override fun connectionLost(cause: Throwable?) {
                vpnWarn { "Directory connection lost: ${BrokerConnection.describe(cause)}" }
                main.post { stateValue = DirectoryState.OFFLINE }
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                val id = topic.substringAfterLast('/')
                val room = if (message.payload.isEmpty()) null
                else Announcement.decode(id, message.payload, System.currentTimeMillis())
                main.post { applyAnnouncement(id, room, cleared = message.payload.isEmpty()) }
            }

            override fun deliveryComplete(token: org.eclipse.paho.client.mqttv3.IMqttDeliveryToken?) {}
        })
        listener = client
        connectListener(client)
        main.postDelayed(sweepTask, SWEEP_MS)
    }

    private fun connectListener(client: MqttAsyncClient) {
        runCatching {
            client.connect(BrokerConnection.options(), null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {}

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    vpnWarn { "Directory connect failed: ${BrokerConnection.describe(exception)}" }
                    main.post {
                        if (listener === client) {
                            stateValue = DirectoryState.OFFLINE
                            main.postDelayed({ if (listener === client) connectListener(client) }, RETRY_MS)
                        }
                    }
                }
            })
        }.onFailure { vpnWarn { "Directory connect error: ${it.message}" } }
    }

    override fun stop() {
        main.removeCallbacks(sweepTask)
        val client = listener ?: return
        listener = null
        stateValue = DirectoryState.IDLE
        roomList.clear()
        lastSeen.clear()
        worker.execute {
            runCatching { client.disconnectForcibly(0, 500) }
            runCatching { client.close() }
        }
    }

    override fun createRoom(name: String, game: String, maxPlayers: Int, hostName: String, private: Boolean, hidden: Boolean): CreatedRoom? {
        if (!VpnNative.isAvailable || hosting != null || guest != null) return null
        if (!TextFilter.isAllowed(name) || !TextFilter.isAllowed(game) || !TextFilter.isAllowed(hostName)) return null
        val seed = VpnNative.nativeGenerateSeed() ?: return null
        val publicKey = VpnNative.nativePublicKey(seed) ?: return null
        val id = VpnNative.nativeRoomId(publicKey) ?: return null
        val hostKey = Announcement.toBase64(publicKey)
        val code = JoinCode.generateFor(roomList)

        val info = RoomInfo(
            id = id,
            name = name.take(VpnConfig.MAX_TEXT_LENGTH),
            game = game.take(VpnConfig.MAX_TEXT_LENGTH),
            hostName = hostName.take(VpnConfig.MAX_TEXT_LENGTH),
            players = 1,
            maxPlayers = maxPlayers.coerceIn(VpnConfig.MIN_PLAYERS, VpnConfig.MAX_PLAYERS),
            hostKey = hostKey,
            isPrivate = private,
            isHidden = private && hidden,
            joinTag = JoinCode.tag(code, hostKey)
        )
        hostReady = null
        hostWait = null
        val host = HostSession(seed, publicKey, info, code.takeIf { private }, worker, object : HostSession.Listener {
            override fun onRoomChanged(info: RoomInfo) {
                main.post { if (hosting?.info?.id == info.id) applyAnnouncement(info.id, info, cleared = false) }
            }

            override fun onMembersChanged(members: List<RoomMember>) {
                main.post { if (hosting != null) RoomSession.updateMembers(members) }
            }

            override fun onReady() {
                main.post {
                    if (hosting?.info?.id != id) return@post
                    hostReady = true
                    hostWait?.invoke(true)
                    hostWait = null
                }
            }

            override fun onFailed(reason: String) {
                main.post {
                    if (hosting?.info?.id != id) return@post
                    hostReady = false
                    hostWait?.invoke(false)
                    hostWait = null
                    removeRoom(id)
                    RoomSession.end(reason)
                }
            }
        })
        hosting = host
        applyAnnouncement(id, info, cleared = false)
        host.start()
        return CreatedRoom(info, code)
    }

    override fun removeRoom(id: String) {
        roomList.removeAll { it.id == id }
        lastSeen.remove(id)

        val host = hosting?.takeIf { it.info.id == id } ?: return
        hosting = null
        host.stop()
        RoomSession.updateMembers(emptyList())
    }

    override fun kickMember(address: String) {
        hosting?.kick(address)
    }

    override fun joinRoom(room: RoomInfo, nickname: String, code: String?, onResult: (JoinResult) -> Unit) {
        if (!VpnNative.isAvailable) return onResult(JoinResult.Rejected("The VPN engine is not available"))
        if (hosting != null || guest != null) return onResult(JoinResult.Rejected("Leave the current room first"))

        lateinit var session: GuestSession
        session = GuestSession(room, nickname, code, worker, object : GuestSession.Listener {
            override fun onJoined(address: String, members: List<RoomMember>) {
                main.post {
                    if (guest !== session) return@post
                    RoomSession.updateMembers(members)
                    onResult(JoinResult.Joined(address))
                }
            }

            override fun onRejected(reason: String, nicknameProblem: Boolean, codeProblem: Boolean) {
                main.post {
                    if (guest !== session) return@post
                    guest = null
                    onResult(JoinResult.Rejected(reason, nicknameProblem, codeProblem))
                }
            }

            override fun onMembersChanged(members: List<RoomMember>) {
                main.post { if (guest === session) RoomSession.updateMembers(members) }
            }

            override fun onLost(reason: String) {
                main.post {
                    if (guest !== session) return@post
                    guest = null
                    RoomSession.end(reason)
                }
            }
        })
        guest = session
        session.start()
    }

    override fun whenHostReady(action: (Boolean) -> Unit) {
        main.post {
            when (hostReady) {
                true -> action(true)
                false -> action(false)
                null -> hostWait = action
            }
        }
    }

    override fun leaveRoom(id: String) {
        val session = guest?.takeIf { it.room.id == id } ?: return
        guest = null
        session.leave()
        RoomSession.updateMembers(emptyList())
    }

    private fun applyAnnouncement(id: String, room: RoomInfo?, cleared: Boolean) {
        if (room == null) {
            if (cleared) {
                roomList.removeAll { it.id == id }
                lastSeen.remove(id)
            }
            return
        }
        lastSeen[id] = System.currentTimeMillis()
        val index = roomList.indexOfFirst { it.id == id }
        if (index >= 0) roomList[index] = room else roomList.add(room)
    }

    private fun shortId() = UUID.randomUUID().toString().take(8)

    private companion object {
        const val SWEEP_MS = 15_000L
        const val RETRY_MS = 5_000L
    }
}
