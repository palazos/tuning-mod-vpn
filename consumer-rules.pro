# Keep JNI entry points when the host app enables R8.
-keep class com.winlator.cmod.vpn.core.VpnNative { *; }
-keep class com.winlator.cmod.vpn.service.RoomVpnService { *; }
