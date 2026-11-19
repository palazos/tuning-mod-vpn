package com.winlator.cmod.vpn.core

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import java.security.MessageDigest
import java.security.SecureRandom

// The one code players share to enter a room. The host generates it when the room is created.
// Announcements carry a short tag derived from the code and the host key, so a guest can find
// the room from the code and check a typo locally. For a private room the host also compares the
// code itself, sent inside the sealed join request.
object JoinCode {
    const val LENGTH = 10

    // No 0/O or 1/I, which are easy to mix up when typed or read aloud.
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val TAG_LENGTH = 4
    private const val GENERATE_ATTEMPTS = 100

    private val random = SecureRandom()

    // Codes are case-insensitive; separators and spaces from a copied code are ignored.
    fun normalize(raw: String): String = lettersAndDigits(raw).uppercase().take(LENGTH)

    fun isValid(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }

    // Returns the normalized code, or null when it is not a well-formed join code.
    fun parse(raw: String): String? = lettersAndDigits(raw).uppercase().takeIf { isValid(it) }

    private fun lettersAndDigits(raw: String) = raw.filter { it in '0'..'9' || it in 'a'..'z' || it in 'A'..'Z' }

    fun format(code: String): String = code.chunked(LENGTH / 2).joinToString("-")

    // The tag also depends on the host key, so a fake room cannot reuse the tag of a real one.
    fun tag(code: String, hostKey: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("wl-join:$code:$hostKey".toByteArray(Charsets.UTF_8))
        val bits = ((digest[0].toInt() and 0xFF) shl 12) or ((digest[1].toInt() and 0xFF) shl 4) or
            ((digest[2].toInt() and 0xFF) shr 4)
        return (0 until TAG_LENGTH).map { ALPHABET[(bits shr (15 - it * 5)) and 31] }.joinToString("")
    }

    fun isValidTag(tag: String): Boolean = tag.length == TAG_LENGTH && tag.all { it in ALPHABET }

    // A room without a tag (announced by an older version) cannot be checked, so it never rules a code out.
    fun matches(room: RoomInfo, code: String): Boolean =
        room.joinTag == null || tag(code, room.hostKey) == room.joinTag

    // Picks a code that does not already point at another announced room. Rooms announced later
    // are not known here; a lookup that still finds several rooms asks the player to choose.
    fun generateFor(rooms: List<RoomInfo>): String {
        var code = randomCode()
        repeat(GENERATE_ATTEMPTS) {
            if (rooms.none { it.joinTag != null && matches(it, code) }) return code
            code = randomCode()
        }
        return code
    }

    // Constant time, so the comparison does not reveal how much of a guess was right.
    fun sameCode(expected: String, received: String): Boolean =
        MessageDigest.isEqual(
            normalize(expected).toByteArray(Charsets.UTF_8),
            normalize(received).toByteArray(Charsets.UTF_8)
        )

    fun copyToClipboard(context: Context, code: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Join code", format(code)))
        Toast.makeText(context, "Join code copied", Toast.LENGTH_SHORT).show()
    }

    fun readClipboard(context: Context): String? {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        return clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    }

    private fun randomCode(): String =
        String(CharArray(LENGTH) { ALPHABET[random.nextInt(ALPHABET.length)] })
}
