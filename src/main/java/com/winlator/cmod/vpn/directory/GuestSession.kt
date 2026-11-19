package com.winlator.cmod.vpn.directory

import android.util.Base64
import com.winlator.cmod.vpn.core.BrokerConnection
import com.winlator.cmod.vpn.core.vpnInfo
import com.winlator.cmod.vpn.core.vpnWarn
import com.winlator.cmod.vpn.core.JoinCode
import com.winlator.cmod.vpn.core.RoomInfo
import com.winlator.cmod.vpn.core.RoomMember
import com.winlator.cmod.vpn.core.VpnConfig
import com.winlator.cmod.vpn.transport.RoomEngine
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.json.JSONObject
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

// A guest's side of a room: asks the host for a slot, then keeps in touch so the host
// knows it is still there. All work runs on the shared single-thread [worker].
internal class GuestSession(
    val room: RoomInfo,
    private val nickname: String,
    // Join code, sent only for private rooms.
    private val code: String?,
    private val worker: ScheduledExecutorService,
    private val listener: Listener
) {
    // Called on the worker thread. Nothing is reported after the session is closed.
    interface Listener {
        fun onJoined(address: String, members: List<RoomMember>)
        fun onRejected(reason: String, nicknameProblem: Boolean, codeProblem: Boolean)
        fun onMembersChanged(members: List<RoomMember>)
        fun onLost(reason: String)
    }

    @Volatile
    private var closed = false

    @Volatile
    private var session: ByteArray? = null
    private var joined = false
    private var counter = 0L
    private var lastHostCounter = 0L
    private var lastReplyAt = 0L
    private var joinTimeout: ScheduledFuture<*>? = null
    private var pings: ScheduledFuture<*>? = null
    private var watchdog: ScheduledFuture<*>? = null
    private var seen: ScheduledFuture<*>? = null
    private var toldHostEndpoint = ""
    private val announcementTopic = "${VpnConfig.TOPIC_ROOT}/${room.id}"

    private val memberId = JoinProtocol.newMemberId()
    private val client = BrokerConnection.newClient("wl-guest-$memberId")

    fun start() {
        client.setCallback(object : MqttCallbackExtended {
            override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                if (reconnect) runCatching {
                    client.subscribe(JoinProtocol.replyTopic(memberId), 1)
                    client.subscribe(announcementTopic, 1)
                }
            }

            override fun connectionLost(cause: Throwable?) {
                vpnWarn { "Guest connection lost: ${BrokerConnection.describe(cause)}" }
            }

            override fun messageArrived(topic: String, message: MqttMessage) {
                val payload = message.payload
                if (topic == announcementTopic) {
                    if (payload.isEmpty()) worker.execute { onAnnouncementCleared() }
                    return
                }
                if (payload.isEmpty() || payload.size > JoinProtocol.MAX_PACKET_BYTES) return
                worker.execute { if (!closed) handleReply(payload) }
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {}
        })
        worker.execute { join() }
    }

    fun leave() {
        worker.execute {
            if (joined && !closed) runCatching { send(JSONObject().put("op", "leave")) }
            shutdown()
        }
    }

    private fun join() {
        try {
            client.connect(BrokerConnection.options()).waitForCompletion(CONNECT_TIMEOUT_MS)
            // Subscribe before asking, otherwise the answer could arrive before we listen.
            client.subscribe(JoinProtocol.replyTopic(memberId), 1).waitForCompletion(CONNECT_TIMEOUT_MS)
            // Optional: lets us notice the room closing without waiting for pings to time out.
            runCatching { client.subscribe(announcementTopic, 1) }

            // Opens our UDP socket and learns the public address the host will need to reach us.
            val engine = RoomEngine.open() ?: return fail("The network engine could not start")
            val request = JSONObject()
                .put("v", VpnConfig.PROTOCOL_VERSION)
                .put("i", memberId)
                .put("n", nickname.take(VpnConfig.MAX_TEXT_LENGTH))
                .put("t", System.currentTimeMillis())
                .put("w", engine.keyHex)
                .put("e", JoinProtocol.candidatesToJson(engine.candidates))
                .apply { if (!code.isNullOrEmpty()) put("k", JoinCode.normalize(code)) }
            val hostKey = Base64.decode(room.hostKey, Base64.NO_WRAP)
            val sealed = JoinProtocol.sealJoin(hostKey, request) ?: return fail("Could not prepare the join request")
            session = sealed.session

            joinTimeout = worker.schedule({ if (!joined) fail("The host did not answer") }, JOIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            publish(JoinProtocol.joinTopic(room.id), sealed.data)
        } catch (e: Exception) {
            vpnWarn { "Join failed: ${BrokerConnection.describe(e)}" }
            fail("Could not reach the room directory")
        }
    }

    private fun handleReply(packet: ByteArray) {
        val key = session ?: return
        val message = JoinProtocol.decrypt(key, JoinProtocol.TO_GUEST, packet) ?: return
        val hostCounter = message.optLong("c")
        if (hostCounter <= lastHostCounter) return
        lastHostCounter = hostCounter
        lastReplyAt = System.currentTimeMillis()

        val op = message.optString("op")
        if (op == "ep") {
            vpnInfo { "Host saw us at ${message.optString("e")}" }
            return
        }
        if (op == "kick" || op == "bye") {
            if (joined) {
                shutdown()
                listener.onLost(
                    if (op == "kick") "The host removed you from the room" else "The host closed the room"
                )
            }
            return
        }

        if (!message.optBoolean("ok")) {
            if (!joined) {
                val why = message.optString("why")
                fail(
                    describe(why),
                    nicknameProblem = why == "name" || why == "bad_name",
                    codeProblem = why == "code" || why == "code_required" || why == "locked"
                )
            }
            return
        }
        val members = JoinProtocol.membersFromJson(message.optJSONArray("r"))
        if (joined) {
            listener.onMembersChanged(members)
            return
        }
        val address = message.optString("ip")
        val hostKey = message.optString("w")
        if (address.isEmpty() || !JoinProtocol.isKeyHex(hostKey)) return fail("The host sent an invalid answer")
        RoomEngine.addPeer(hostKey, VpnConfig.HOST_ADDRESS, JoinProtocol.candidatesFromJson(message.optJSONArray("e")), initiator = true)

        joined = true
        joinTimeout?.cancel(false)
        pings = worker.scheduleWithFixedDelay({ ping() }, VpnConfig.PING_MS, VpnConfig.PING_MS, TimeUnit.MILLISECONDS)
        seen = worker.scheduleWithFixedDelay({ runCatching { publishSeen() } }, 500, 500, TimeUnit.MILLISECONDS)
        watchdog = worker.scheduleWithFixedDelay(
            { checkHost() }, VpnConfig.CHECK_MS, VpnConfig.CHECK_MS, TimeUnit.MILLISECONDS
        )
        listener.onJoined(address, members)
    }

    private fun publishSeen() {
        if (!joined || closed) return
        val endpoint = RoomEngine.status().firstOrNull { it.address == VpnConfig.HOST_ADDRESS }?.endpoint ?: return
        if (endpoint.isEmpty() || endpoint == toldHostEndpoint) return
        runCatching { send(JSONObject().put("op", "ep").put("e", endpoint)) }.onSuccess {
            toldHostEndpoint = endpoint
            vpnInfo { "Saw host at $endpoint" }
        }
    }

    private fun ping() {
        if (closed) return
        runCatching { send(JSONObject().put("op", "ping")) }
            .onFailure { vpnWarn { "Ping failed: ${it.message}" } }
    }

    private fun checkHost() {
        if (closed) return
        if (System.currentTimeMillis() - lastReplyAt > VpnConfig.MEMBER_STALE_MS) {
            shutdown()
            listener.onLost("The host stopped answering")
        }
    }

    // The announcement is gone: the host closed the room or dropped off the broker. Anyone can
    // publish an empty message there, so only leave if the host does not answer a ping.
    private fun onAnnouncementCleared() {
        if (!joined || closed) return
        val askedAt = System.currentTimeMillis()
        ping()
        worker.schedule({
            if (joined && !closed && lastReplyAt < askedAt) {
                shutdown()
                listener.onLost("The host closed the room")
            }
        }, CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)
    }

    private fun send(message: JSONObject) {
        val key = session ?: return
        message.put("c", ++counter)
        val packet = JoinProtocol.encrypt(key, JoinProtocol.TO_HOST, message) ?: return
        publish(JoinProtocol.talkTopic(room.id, memberId), packet)
    }

    private fun publish(topic: String, payload: ByteArray) {
        val message = MqttMessage(payload).apply { qos = 1 }
        client.publish(topic, message).waitForCompletion(PUBLISH_TIMEOUT_MS)
    }

    private fun fail(reason: String, nicknameProblem: Boolean = false, codeProblem: Boolean = false) {
        if (closed) return
        shutdown()
        listener.onRejected(reason, nicknameProblem, codeProblem)
    }

    private fun describe(code: String) = when (code) {
        "full" -> "The room is full"
        "version" -> "The host runs an incompatible version"
        "name" -> "That nickname already exists in this room"
        "bad_name" -> "That nickname is not allowed"
        "code" -> "Wrong join code"
        "code_required" -> "This room needs a join code"
        "locked" -> "Too many wrong codes. Try again later"
        else -> "The host refused the request"
    }

    private fun shutdown() {
        if (closed) return
        closed = true
        joinTimeout?.cancel(false)
        pings?.cancel(false)
        watchdog?.cancel(false)
        seen?.cancel(false)
        runCatching { client.disconnectForcibly(0, 500) }
        runCatching { client.close() }
        RoomEngine.close()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = BrokerConnection.CONNECT_TIMEOUT_MS
        const val PUBLISH_TIMEOUT_MS = 5_000L
        const val JOIN_TIMEOUT_MS = 12_000L
        const val CLOSE_GRACE_MS = 8_000L
    }
}
