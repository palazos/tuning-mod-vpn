package com.winlator.cmod.vpn.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

object RoomVpnLauncher {
    private var requestCounter = 0

    fun start(activity: ComponentActivity, address: String, isHost: Boolean, onResult: (Boolean) -> Unit) {
        val consent = VpnService.prepare(activity)
        if (consent == null) {
            startService(activity, address, isHost)
            onResult(true)
            return
        }

        lateinit var launcher: ActivityResultLauncher<Intent>
        launcher = activity.activityResultRegistry.register(
            "vpn_consent_${requestCounter++}",
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            launcher.unregister()
            val granted = result.resultCode == Activity.RESULT_OK
            if (granted) startService(activity, address, isHost)
            onResult(granted)
        }
        launcher.launch(consent)
    }

    fun stop(context: Context) {
        context.startService(
            Intent(context, RoomVpnService::class.java).setAction(RoomVpnService.ACTION_STOP)
        )
    }

    private fun startService(context: Context, address: String, isHost: Boolean) {
        context.startService(
            Intent(context, RoomVpnService::class.java)
                .putExtra(RoomVpnService.EXTRA_ADDRESS, address)
                .putExtra(RoomVpnService.EXTRA_HOST, isHost)
        )
    }
}
