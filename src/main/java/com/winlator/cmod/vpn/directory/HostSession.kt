package com.winlator.cmod.vpn.directory

import com.winlator.cmod.vpn.core.BrokerConnection
import com.winlator.cmod.vpn.core.vpnInfo
import com.winlator.cmod.vpn.core.vpnWarn
import com.winlator.cmod.vpn.core.Nickname
import com.winlator.cmod.vpn.core.JoinCode
import com.winlator.cmod.vpn.core.RoomInfo
import com.winlator.cmod.vpn.core.RoomMember
import com.winlator.cmod.vpn.core.TextFilter
import com.winlator.cmod.vpn.core.VpnConfig
import com.winlator.cmod.vpn.transport.RoomEngine
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.json.JSONObject
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// Everything a room host does on the broker: announce the room, answer join requests
// and keep the member list. All work runs on the shared single-thread [worker].
internal class HostSession(
    private val seed: ByteArray,
    private val publicKey: ByteArray,
    initial: RoomInfo,
    // Join code of a private room, compared on every join; null for a public room.
    private val code: String?,
    private val worker: ScheduledExecutorService,
    private val listener: Listener
) {
    // Called on the worker thread.
    interface Listener {
        fun onRoomChanged(info: RoomInfo)
        fun onMembersChanged(members: List<RoomMember>)

        // The public port is known, so the VPN can come up. Called once.
        fun onReady()

        // The room cannot be hosted from this network; it was never announced.
        fun onFailed(reason: String)
    }

    @Volatile
    var info: RoomInfo = initial
        private set

    @Volatile
    private var closed = false

    @Volatile
    private var local: RoomEngine.Local? = null
    private val table = MemberTable(initial.hostName, initial.maxPlayers)
    private val client = BrokerConnection.newClient("wl-host-${initial.id}")
    private var refresh: ScheduledFuture<*>? = null
    private var sweep: ScheduledFuture<*>? = null
    private var replyCounter = 0L
    private var readySent = false
    private var seen: ScheduledFuture<*>? = null
    private val toldEndpoint = HashMap<String, String>()

    // Wrong codes are counted per room, not per guest: the member id is chosen by the sender.
    private var wrongCodes = 0
    private var lockedUntil = 0L
    private var lockLevel = 0
    private var lastWrongAt = 0L

    fun start() {
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                // The session is clean, so subscriptions are lost on every reconnect.
                runCatching {
                    client.subscribe(JoinProtocol.joinTopic(info.id), 1)
                    client.subscribe(JoinProtocol.talkFilter(info.id), 1)
                }
                // The broker may have fired the last will during the outage; guests are still here.
                if (reconnect) worker.execute { if (!closed) runCatching { announce() } }
            }

            override fun connectionLost(cause: Throwable?) {
                vpnWarn { "Host connection lost: ${BrokerConnection.describe(cause)}" }
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                val payload = message.payload
                if (payload.isEmpty() || payload.size > JoinProtocol.MAX_PACKET_BYTES) return
                worker.execute { if (!closed) handle(topic, payload) }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        listener.onMembersChanged(table.snapshot())
        refresh = worker.scheduleWithFixedDelay({ tick() }, 0, VpnConfig.REFRESH_MS, TimeUnit.MILLISECONDS)
        seen = worker.scheduleWithFixedDelay({ runCatching { publishSeen() } }, 500, 500, TimeUnit.MILLISECONDS)
        sweep = worker.scheduleWithFixedDelay(
            { runCatching { expireMembers() } }, VpnConfig.CHECK_MS, VpnConfig.CHECK_MS, TimeUnit.MILLISECONDS
        )
    }

    fun stop() {
        closed = true
        refresh?.cancel(false)
        sweep?.cancel(false)
        seen?.cancel(false)
        worker.execute {
            // Tell guests now instead of letting them time out. Stop at the first failure so an
            // unreachable broker cannot hold the worker for one timeout per guest.
            for (member in table.all()) {
                if (runCatching { reply(member.id, member.session, JSONObject().put("op", "bye")) }.isFailure) break
            }
            // A clean disconnect does not fire the last will, so clear the announcement explicitly.
            runCatching { publishAnnouncement(ByteArray(0)) }
            runCatching { client.disconnectForcibly(0, 500) }
            runCatching { client.close() }
            RoomEngine.close()
        }
    }

    // Removes a guest right away and tells it why, instead of leaving it to time out.
    fun kick(address: String) {
        worker.execute {
            if (closed) return@execute
            val member = table.findByAddress(address) ?: return@execute
            runCatching { reply(member.id, member.session, JSONObject().put("op", "kick")) }
            table.remove(member.id)
            onGuestLeft(member)
            membersChanged(republish = true)
        }
    }

    private fun tick() {
        if (closed) return
        try {
            if (local == null && !prepareEngine()) return
            ensureConnected()
            expireMembers()
            announce()
        } catch (e: Exception) {
            vpnWarn { "Host refresh failed: ${BrokerConnection.describe(e)}" }
        }
    }

    private fun expireMembers() {
        if (closed) return
        val expired = table.expire(System.currentTimeMillis(), VpnConfig.MEMBER_STALE_MS)
        expired.forEach { onGuestLeft(it) }
        if (expired.isNotEmpty() || info.players != table.playerCount) membersChanged(republish = false)
    }

    // Guests must be able to reach us, so a room is only announced once we know our public address.
    private fun prepareEngine(): Boolean {
        val opened = RoomEngine.open()
        val problem = when {
            closed -> return false
            opened == null -> "The network engine could not start"
            opened.mapped.isEmpty() && opened.forwarded.isEmpty() ->
                "Could not find your public address. Check your internet connection"
            // A port forwarded on the router works even behind a strict NAT.
            opened.nat == "symmetric" && opened.forwarded.isEmpty() ->
                "This network cannot host rooms (symmetric NAT). Try another network"
            else -> null
        }
        if (problem != null) {
            closed = true
            refresh?.cancel(false)
            RoomEngine.close()
            listener.onFailed(problem)
            return false
        }
        local = opened
        if (!readySent) {
            readySent = true
            listener.onReady()
        }
        return true
    }

    // The carrier keeps a dead flow toward the port a guest just used. With nobody left on it,
    // replace the socket so the next guest is a first connection. Peers still inside are not touched:
    // this runs only when the room has no guests.
    private fun rotatePort() {
        val opened = RoomEngine.reopen()
        if (opened == null) {
            vpnWarn { "Could not open a fresh host port" }
            return
        }
        local = opened
        vpnInfo { "Host port rotated: ${opened.candidates}" }
    }

    private fun onGuestLeft(member: MemberTable.Member) {
        RoomEngine.removePeer(member.address)
        toldEndpoint.remove(member.address)
        if (member.door != 0) RoomEngine.closeDoor(member.door)
        if (table.all().isEmpty()) rotatePort()
    }

    // Tells a guest the port we actually received them on, once a probe or handshake arrives.
    private fun publishSeen() {
        if (closed || local == null) return
        for (peer in RoomEngine.status()) {
            if (peer.endpoint.isEmpty() || toldEndpoint[peer.address] == peer.endpoint) continue
            val member = table.findByAddress(peer.address) ?: continue
            runCatching {
                reply(member.id, member.session, JSONObject().put("op", "ep").put("e", peer.endpoint))
            }.onSuccess {
                toldEndpoint[peer.address] = peer.endpoint
                vpnInfo { "Saw ${peer.address} at ${peer.endpoint}" }
            }
        }
    }

    private fun ensureConnected() {
        if (client.isConnected) return
        val options = BrokerConnection.options().apply {
            setWill(announcementTopic(), ByteArray(0), 1, true)
        }
        client.connect(options).waitForCompletion(CONNECT_TIMEOUT_MS)
    }

    private fun announce() {
        val payload = Announcement.encode(seed, publicKey, info, System.currentTimeMillis()) ?: return
        publishAnnouncement(payload)
    }

    private fun publishAnnouncement(payload: ByteArray) {
        val message = MqttMessage(payload).apply {
            qos = 1
            isRetained = true
        }
        client.publish(announcementTopic(), message).waitForCompletion(PUBLISH_TIMEOUT_MS)
    }

    private fun announcementTopic() = "${VpnConfig.TOPIC_ROOT}/${info.id}"

    private fun handle(topic: String, payload: ByteArray) {
        try {
            if (topic == JoinProtocol.joinTopic(info.id)) {
                handleJoin(payload)
            } else if (topic.startsWith("${VpnConfig.TALK_ROOT}/${info.id}/")) {
                handleTalk(topic.substringAfterLast('/'), payload)
            }
        } catch (e: Exception) {
            vpnWarn { "Host message failed: ${e.message}" }
        }
    }

    private fun handleJoin(packet: ByteArray) {
        val opened = JoinProtocol.openJoin(seed, packet) ?: return
        val request = JoinProtocol.parse(opened.data) ?: return
        val memberId = request.optString("i")
        val name = Nickname.clean(request.optString("n"), VpnConfig.MAX_TEXT_LENGTH)
        val now = System.currentTimeMillis()
        if (!JoinProtocol.isValidMemberId(memberId) || name.isEmpty()) return
        if (Math.abs(now - request.optLong("t")) > JoinProtocol.MAX_CLOCK_SKEW_MS) return

        if (request.optInt("v") != VpnConfig.PROTOCOL_VERSION) {
            reply(memberId, opened.session, JSONObject().put("ok", false).put("why", "version"))
            return
        }
        if (code != null && !acceptCode(request.optString("k"), now, memberId, opened.session)) return
        // The guest checks its own name before asking; this covers a modified client.
        if (!TextFilter.isAllowed(name)) {
            reply(memberId, opened.session, JSONObject().put("ok", false).put("why", "bad_name"))
            return
        }
        if (table.nameTaken(name, memberId)) {
            reply(memberId, opened.session, JSONObject().put("ok", false).put("why", "name"))
            return
        }
        val guestKey = request.optString("w")
        if (!JoinProtocol.isKeyHex(guestKey)) return
        val guestCandidates = JoinProtocol.candidatesFromJson(request.optJSONArray("e"))
        table.find(memberId)?.let { if (it.door != 0) RoomEngine.closeDoor(it.door) }

        val member = table.join(memberId, name, opened.session, now)
        if (member == null) {
            reply(memberId, opened.session, JSONObject().put("ok", false).put("why", "full"))
            return
        }
        val engine = local ?: return
        // Someone is already inside, so this guest gets its own port. The others keep theirs.
        val needsDoor = table.all().any { it.id != memberId }
        val door = if (needsDoor) RoomEngine.openDoor() else null
        if (door != null) member.door = door.slot
        val hostCandidates = door?.candidates ?: engine.candidates
        // Start punching toward the guest before it learns our address, so both sides open their NAT together.
        RoomEngine.addPeer(guestKey, member.address, guestCandidates, initiator = false, slot = member.door)
        reply(
            memberId,
            member.session,
            JSONObject()
                .put("ok", true)
                .put("ip", member.address)
                .put("w", engine.keyHex)
                .put("e", JoinProtocol.candidatesToJson(hostCandidates))
                .put("r", members())
        )
        membersChanged(republish = true)
    }

    // Replies with the refusal and returns false when the code is missing, wrong or the room is locked.
    private fun acceptCode(received: String, now: Long, memberId: String, session: ByteArray): Boolean {
        val why = when {
            now < lockedUntil -> "locked"
            received.isEmpty() -> "code_required"
            JoinCode.sameCode(code.orEmpty(), received) -> {
                wrongCodes = 0
                return true
            }
            else -> {
                if (now - lastWrongAt > LOCK_DECAY_MS) lockLevel = 0
                lastWrongAt = now
                if (++wrongCodes >= MAX_WRONG_CODES) {
                    wrongCodes = 0
                    // Each lock lasts twice as long as the previous one, so guessing stays impractical.
                    lockedUntil = now + minOf(CODE_LOCK_MS shl lockLevel, MAX_LOCK_MS)
                    if (lockLevel < MAX_LOCK_LEVEL) lockLevel++
                }
                "code"
            }
        }
        reply(memberId, session, JSONObject().put("ok", false).put("why", why))
        return false
    }

    private fun handleTalk(memberId: String, packet: ByteArray) {
        val member = table.find(memberId) ?: return
        val message = JoinProtocol.decrypt(member.session, JoinProtocol.TO_HOST, packet) ?: return
        // Counters stop an old message from being replayed.
        val counter = message.optLong("c")
        if (counter <= member.lastCounter) return
        member.lastCounter = counter
        member.lastSeen = System.currentTimeMillis()

        when (message.optString("op")) {
            "ping" -> reply(memberId, member.session, JSONObject().put("ok", true).put("r", members()))
            "ep" -> vpnInfo { "Guest reports our visible port ${message.optString("e")}" }
            "leave" -> table.remove(memberId)?.let {
                onGuestLeft(it)
                membersChanged(republish = true)
            }
        }
    }

    private fun members() = JoinProtocol.membersToJson(table.snapshot())

    private fun membersChanged(republish: Boolean) {
        info = info.copy(players = table.playerCount)
        listener.onRoomChanged(info)
        listener.onMembersChanged(table.snapshot())
        if (republish) runCatching { announce() }
    }

    private fun reply(memberId: String, session: ByteArray, message: JSONObject) {
        message.put("c", ++replyCounter)
        val packet = JoinProtocol.encrypt(session, JoinProtocol.TO_GUEST, message) ?: return
        val mqtt = MqttMessage(packet).apply { qos = 1 }
        client.publish(JoinProtocol.replyTopic(memberId), mqtt).waitForCompletion(PUBLISH_TIMEOUT_MS)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = BrokerConnection.CONNECT_TIMEOUT_MS
        const val PUBLISH_TIMEOUT_MS = 5_000L
        const val MAX_WRONG_CODES = 5
        const val CODE_LOCK_MS = 60_000L
        const val MAX_LOCK_LEVEL = 6
        const val MAX_LOCK_MS = 3_600_000L
        const val LOCK_DECAY_MS = 3_600_000L
    }
}
