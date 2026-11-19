package com.winlator.cmod.vpn.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import com.winlator.cmod.vpn.core.BrokerConnection
import com.winlator.cmod.vpn.core.NetworkEnv
import com.winlator.cmod.vpn.core.RoomSession
import com.winlator.cmod.vpn.core.VpnFeature
import com.winlator.cmod.vpn.core.VpnNative
import com.winlator.cmod.vpn.core.VpnPackage

// Single integration surface used by the Library toolbar and headers.
object VpnRoomsEntry {
    const val MENU_ID = 0x5652

    private var appContext: Context? = null

    @JvmStatic
    fun attach(context: Context) {
        appContext = context.applicationContext
        VpnNative.attach(context)
    }

    @JvmStatic
    fun isModuleReady(): Boolean {
        val ctx = appContext ?: return false
        return VpnFeature.ENABLED && VpnPackage.isInstalled(ctx)
    }

    @JvmStatic
    fun hasActiveRoom(): Boolean = isModuleReady() && RoomSession.current != null

    @JvmStatic
    fun keepsProcessAlive(): Boolean = hasActiveRoom()

    @JvmStatic
    fun show(context: Context) {
        attach(context)
        if (!isModuleReady()) return
        if (!VpnNative.ensureLoaded()) return
        NetworkEnv.attach(context)
        BrokerConnection.attach(context)
        findActivity(context)?.let { RoomsDialog.show(it) }
    }

    private fun findActivity(context: Context): ComponentActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is ComponentActivity) return current
            current = current.baseContext
        }
        return null
    }
}
