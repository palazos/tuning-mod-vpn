package com.winlator.cmod.vpn.directory

import android.util.Base64
import com.winlator.cmod.vpn.core.JoinCode
import com.winlator.cmod.vpn.core.Nickname
import com.winlator.cmod.vpn.core.RoomInfo
import com.winlator.cmod.vpn.core.TextFilter
import com.winlator.cmod.vpn.core.VpnConfig
import com.winlator.cmod.vpn.core.VpnNative
import org.json.JSONObject

// Wire format: {"b": "<body json>", "s": "<base64 signature of the body bytes>"}.
// The body carries the host key; the topic id must be derived from that key, so a
// third party cannot publish or overwrite an announcement for someone else's room.
// It never contains network addresses; those are exchanged privately when joining.
internal object Announcement {
    private const val MAX_CLOCK_SKEW_MS = 10 * 60_000L

    fun encode(seed: ByteArray, publicKey: ByteArray, room: RoomInfo, nowMs: Long): ByteArray? {
        val body = JSONObject()
            .put("v", VpnConfig.PROTOCOL_VERSION)
            .put("k", toBase64(publicKey))
            .put("n", room.name)
            .put("g", room.game)
            .put("h", room.hostName)
            .put("p", room.players)
            .put("m", room.maxPlayers)
            .put("t", nowMs)
            .apply {
                if (room.isPrivate) put("x", true)
                if (room.isHidden) put("hd", true)
                room.joinTag?.let { put("c", it) }
            }
            .toString()
        val signature = VpnNative.nativeSign(seed, body.toByteArray(Charsets.UTF_8)) ?: return null
        return JSONObject()
            .put("b", body)
            .put("s", toBase64(signature))
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    // Returns null for anything malformed, unsigned, forged or out of range.
    fun decode(roomId: String, payload: ByteArray, nowMs: Long): RoomInfo? = try {
        val envelope = JSONObject(String(payload, Charsets.UTF_8))
        val bodyText = envelope.getString("b")
        val signature = Base64.decode(envelope.getString("s"), Base64.NO_WRAP)
        val body = JSONObject(bodyText)

        val publicKey = Base64.decode(body.getString("k"), Base64.NO_WRAP)
        val sentAt = body.getLong("t")
        val name = body.getString("n").trim()
        val game = body.getString("g").trim()
        val hostName = Nickname.clean(body.getString("h"), VpnConfig.MAX_TEXT_LENGTH)
        val players = body.getInt("p")
        val maxPlayers = body.getInt("m")

        val valid = body.getInt("v") == VpnConfig.PROTOCOL_VERSION &&
            VpnNative.nativeRoomId(publicKey) == roomId &&
            VpnNative.nativeVerify(publicKey, bodyText.toByteArray(Charsets.UTF_8), signature) &&
            Math.abs(nowMs - sentAt) <= MAX_CLOCK_SKEW_MS &&
            name.isNotEmpty() &&
            name.length <= VpnConfig.MAX_TEXT_LENGTH &&
            game.length <= VpnConfig.MAX_TEXT_LENGTH &&
            hostName.length <= VpnConfig.MAX_TEXT_LENGTH &&
            TextFilter.isAllowed(name) && TextFilter.isAllowed(game) && TextFilter.isAllowed(hostName) &&
            maxPlayers in VpnConfig.MIN_PLAYERS..VpnConfig.MAX_PLAYERS &&
            players in 1..maxPlayers

        if (valid) {
            RoomInfo(
                roomId, name, game, hostName, players, maxPlayers, toBase64(publicKey),
                isPrivate = body.optBoolean("x", false),
                isHidden = body.optBoolean("x", false) && body.optBoolean("hd", false),
                joinTag = body.optString("c").takeIf { JoinCode.isValidTag(it) }
            )
        } else null
    } catch (e: Exception) {
        null
    }

    fun toBase64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
}
