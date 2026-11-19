package com.winlator.cmod.vpn.core

import android.util.Log
import com.winlator.cmod.vpn.BuildConfig

// Library DEBUG follows the host variant; release folds these calls away.
internal inline fun vpnInfo(message: () -> String) {
    if (BuildConfig.DEBUG) Log.i("WinlatorVpn", message())
}

internal inline fun vpnWarn(message: () -> String) {
    if (BuildConfig.DEBUG) Log.w("WinlatorVpn", message())
}
