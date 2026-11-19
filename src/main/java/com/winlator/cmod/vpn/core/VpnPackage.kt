package com.winlator.cmod.vpn.core

import android.content.Context
import java.io.File

/** On-device install location for the VPN Tuning Module package. */
object VpnPackage {
    const val MODULE_ID = "vpn"

    fun moduleDir(context: Context): File =
        File(context.applicationContext.filesDir, "tuning-modules/$MODULE_ID")

    fun isInstalled(context: Context): Boolean =
        File(moduleDir(context), "module.json").isFile

    fun nativeLib(context: Context): File =
        File(moduleDir(context), "lib/arm64-v8a/libwinlator_vpn.so")
}
