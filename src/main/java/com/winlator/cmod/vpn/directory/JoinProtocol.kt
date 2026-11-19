package com.winlator.cmod.vpn.directory

import com.winlator.cmod.vpn.core.Nickname
import com.winlator.cmod.vpn.core.RoomMember
import com.winlator.cmod.vpn.core.TextFilter
import com.winlator.cmod.vpn.core.VpnConfig
import com.winlator.cmod.vpn.core.VpnNative
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

// Private channel between a guest and the host of a room, carried over the broker.
//  - JOIN_ROOT/<room>:            first request, sealed to the host key (only the host can read it)
//  - TALK_ROOT/<room>/<member>:   later requests, encrypted with the session key of that join
//  - REPLY_ROOT/<member>:         every answer from the host, encrypted the same way
// The broker only ever sees ciphertext, and a guest can only get a readable answer from
// whoever holds the host's private key, so the host key is pinned by construction.
internal object JoinProtocol {
    const val TO_HOST = 0
    const val TO_GUEST = 1
    const val MAX_PACKET_BYTES = 4096
    const val MAX_CLOCK_SKEW_MS = 10 * 60_000L

    private const val SESSION_LEN = 32
    private const val MEMBER_ID_LENGTH = 12

    class Sealed(val session: ByteArray, val data: ByteArray)

    fun joinTopic(roomId: String) = "${VpnConfig.JOIN_ROOT}/$roomId"

    fun talkTopic(roomId: String, memberId: String) = "${VpnConfig.TALK_ROOT}/$roomId/$memberId"

    fun talkFilter(roomId: String) = "${VpnConfig.TALK_ROOT}/$roomId/+"

    fun replyTopic(memberId: String) = "${VpnConfig.REPLY_ROOT}/$memberId"

    fun newMemberId(): String = UUID.randomUUID().toString().replace("-", "").take(MEMBER_ID_LENGTH)

    fun isValidMemberId(id: String) =
        id.length == MEMBER_ID_LENGTH && id.all { it in '0'..'9' || it in 'a'..'f' }

    fun sealJoin(hostKey: ByteArray, request: JSONObject): Sealed? =
        split(VpnNative.nativeSealJoin(hostKey, request.toString().toByteArray(Charsets.UTF_8)))

    // The data of the returned value is the decrypted request.
    fun openJoin(seed: ByteArray, packet: ByteArray): Sealed? =
        split(VpnNative.nativeOpenJoin(seed, packet))

    fun encrypt(session: ByteArray, direction: Int, message: JSONObject): ByteArray? =
        VpnNative.nativeEncrypt(session, direction, message.toString().toByteArray(Charsets.UTF_8))

    fun decrypt(session: ByteArray, direction: Int, packet: ByteArray): JSONObject? {
        if (packet.size > MAX_PACKET_BYTES) return null
        val plain = VpnNative.nativeDecrypt(session, direction, packet) ?: return null
        return parse(plain)
    }

    fun parse(bytes: ByteArray): JSONObject? = try {
        JSONObject(String(bytes, Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    fun membersToJson(members: List<RoomMember>): JSONArray = JSONArray().apply {
        members.forEach { put(JSONObject().put("n", it.name).put("a", it.address).put("h", it.isHost)) }
    }

    fun membersFromJson(array: JSONArray?): List<RoomMember> {
        if (array == null) return emptyList()
        return (0 until minOf(array.length(), VpnConfig.MAX_PLAYERS)).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val received = Nickname.clean(item.optString("n"), VpnConfig.MAX_TEXT_LENGTH)
            val name = if (TextFilter.isAllowed(received)) received else BLOCKED_NAME
            val address = item.optString("a").take(MAX_ADDRESS_LENGTH)
            if (name.isEmpty() || address.isEmpty()) null else RoomMember(name, address, item.optBoolean("h"))
        }
    }

    // Shown instead of a received name that the text filter refuses.
    private const val BLOCKED_NAME = "Player"
    private const val MAX_ADDRESS_LENGTH = 15
    private const val MAX_CANDIDATES = 16
    private const val MAX_CANDIDATE_LENGTH = 47

    // WireGuard public keys travel as 64 hex characters.
    fun isKeyHex(text: String) = text.length == 64 && text.all { it in '0'..'9' || it in 'a'..'f' }

    fun candidatesToJson(candidates: List<String>): JSONArray = JSONArray(candidates.take(MAX_CANDIDATES))

    fun candidatesFromJson(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        return (0 until minOf(array.length(), MAX_CANDIDATES))
            .map { array.optString(it) }
            .filter { it.isNotEmpty() && it.length <= MAX_CANDIDATE_LENGTH }
    }
    private fun split(joined: ByteArray?): Sealed? {
        if (joined == null || joined.size < SESSION_LEN) return null
        return Sealed(joined.copyOfRange(0, SESSION_LEN), joined.copyOfRange(SESSION_LEN, joined.size))
    }
}
