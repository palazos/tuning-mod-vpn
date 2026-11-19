package com.winlator.cmod.vpn.core

import android.content.Context

// The name other players see. The user picks it once and it stays on the device.
object Nickname {
    const val MAX_LENGTH = 20

    private const val PREFS = "vpn_rooms"
    private const val KEY = "nickname"

    // For text fields: drops characters that cannot be shown, but does not trim, so a space
    // between two words can still be typed.
    fun limit(raw: String, max: Int = MAX_LENGTH): String =
        raw.filter { !it.isISOControl() && it.category != CharCategory.FORMAT }.take(max)

    // Final form of a name. Names also arrive from other players, so this is applied to those
    // too: format characters (such as right-to-left overrides) would let one pose as another.
    fun clean(raw: String, max: Int = MAX_LENGTH): String =
        limit(raw, Int.MAX_VALUE).trim().replace(WHITESPACE, " ").take(max).trim()

    fun load(context: Context): String = clean(prefs(context).getString(KEY, "").orEmpty())

    fun save(context: Context, value: String) {
        prefs(context).edit().putString(KEY, clean(value)).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val WHITESPACE = Regex("\\s+")
}
