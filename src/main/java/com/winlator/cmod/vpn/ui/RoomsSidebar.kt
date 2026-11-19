package com.winlator.cmod.vpn.ui

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.TextUtils
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.winlator.cmod.vpn.core.ActiveRoom
import com.winlator.cmod.vpn.core.JoinCode
import com.winlator.cmod.vpn.core.RoomMember
import com.winlator.cmod.vpn.core.RoomSession
import com.winlator.cmod.vpn.directory.RoomDirectoryProvider
import com.winlator.cmod.vpn.transport.RoomEngine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Room panel of the in-game sidebar: players of the active room with their link and round trip.
// The host can remove players from here. Host layout ids live in the app package.
class RoomsSidebar(private val activity: Activity, root: View) {
    private val main = Handler(Looper.getMainLooper())
    private val title: TextView = root.findViewById(appId("TVRoomsTitle"))
    private val copyCode: ImageView = root.findViewById(appId("IVRoomCodeCopy"))
    private val info: TextView = root.findViewById(appId("TVRoomsInfo"))
    private val list: LinearLayout = root.findViewById(appId("LLRoomPlayers"))
    private val empty: TextView = root.findViewById(appId("TVRoomsEmpty"))

    private var worker: ExecutorService? = null
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        worker = Executors.newSingleThreadExecutor()
        main.post(tick)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
        worker?.shutdownNow()
        worker = null
    }

    private fun refresh() {
        val active = RoomSession.current
        if (active == null) {
            render(null, emptyList(), emptyMap())
            return
        }
        val members = RoomSession.members
        // The engine status takes the engine lock, so keep it off the main thread.
        worker?.execute {
            val peers = RoomEngine.status().associateBy { it.address }
            main.post { if (running) render(active, members, peers) }
        }
    }

    private fun render(active: ActiveRoom?, members: List<RoomMember>, peers: Map<String, RoomEngine.PeerState>) {
        list.removeAllViews()
        if (active == null) {
            title.text = "Room"
            copyCode.visibility = View.GONE
            info.visibility = View.GONE
            empty.text = RoomSession.notice ?: "You are not in a room"
            empty.visibility = View.VISIBLE
            return
        }
        empty.visibility = View.GONE
        title.text = active.room.name
        val joinCode = active.code?.takeIf { active.isHost }
        copyCode.visibility = if (joinCode != null) View.VISIBLE else View.GONE
        copyCode.setOnClickListener { joinCode?.let { JoinCode.copyToClipboard(activity, it) } }
        info.visibility = View.VISIBLE
        info.text = (if (active.isHost) "Hosting" else "Joined") +
            " - your address ${active.localAddress} - ${members.size}/${active.room.maxPlayers} players" +
            (connectionHint(active, peers)?.let { "\n$it" } ?: "")
        members.forEach { list.addView(row(active, it, peers), rowParams()) }
    }

    private fun row(active: ActiveRoom, member: RoomMember, peers: Map<String, RoomEngine.PeerState>): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(appDrawable("sidebar_card"))
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        // Nickname in bold, then the link detail in the regular weight.
        val detail = playerDetail(active, member, peers)
        val line = SpannableString(member.name + detail).apply {
            setSpan(StyleSpan(Typeface.BOLD), 0, member.name.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val text = label("", 14f, appAttr("ingameSidebarOnSurface")).apply { this.text = line }
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        if (active.isHost && !member.isHost) {
            val kick = label("Kick", 12f, appAttr("ingameSidebarOnSurface"), bold = true).apply {
                gravity = Gravity.CENTER
                setBackgroundResource(appDrawable("sidebar_action"))
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener {
                    AlertDialog.Builder(activity)
                        .setMessage("Remove ${member.name} from the room?")
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            RoomDirectoryProvider.instance.kickMember(member.address)
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }
            row.addView(kick, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
        }
        return row
    }

    private fun label(value: String, size: Float, colorAttr: Int, bold: Boolean = false) = TextView(activity).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color(colorAttr))
        if (bold) setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun rowParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) }

    private fun color(attr: Int): Int {
        if (attr == 0) return Color.WHITE
        val value = TypedValue()
        return if (activity.theme.resolveAttribute(attr, value, true)) value.data else Color.WHITE
    }

    private fun dp(value: Int) = Math.round(value * activity.resources.displayMetrics.density)

    private fun appId(name: String): Int =
        activity.resources.getIdentifier(name, "id", activity.packageName)

    private fun appDrawable(name: String): Int =
        activity.resources.getIdentifier(name, "drawable", activity.packageName)

    private fun appAttr(name: String): Int =
        activity.resources.getIdentifier(name, "attr", activity.packageName)

    private companion object {
        const val REFRESH_MS = 2_000L
    }
}
