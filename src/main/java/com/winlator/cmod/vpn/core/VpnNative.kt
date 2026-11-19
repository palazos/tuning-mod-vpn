package com.winlator.cmod.vpn.core

import java.util.concurrent.atomic.AtomicBoolean

object VpnNative {
    private val loaded = AtomicBoolean(false)
    private var appContext: android.content.Context? = null

    @JvmStatic
    fun attach(context: android.content.Context) {
        appContext = context.applicationContext
    }

    val isAvailable: Boolean
        get() = ensureLoaded()

    @JvmStatic
    fun ensureLoaded(): Boolean {
        if (loaded.get()) return true
        synchronized(this) {
            if (loaded.get()) return true
            val ctx = appContext ?: return false
            val lib = VpnPackage.nativeLib(ctx)
            if (!lib.isFile) return false
            return try {
                System.load(lib.absolutePath)
                loaded.set(true)
                true
            } catch (e: UnsatisfiedLinkError) {
                vpnWarn { "Native library not available: ${e.message}" }
                false
            }
        }
    }

    @JvmStatic
    fun noteUnloaded() {
        // Native libs cannot be unloaded; UI gates hide Rooms until the next cold start.
    }

    @JvmStatic external fun nativeVersion(): String
    @JvmStatic external fun nativeSelfTest(): String
    @JvmStatic external fun nativeGenerateSeed(): ByteArray?
    @JvmStatic external fun nativePublicKey(seed: ByteArray): ByteArray?
    @JvmStatic external fun nativeSign(seed: ByteArray, message: ByteArray): ByteArray?
    @JvmStatic external fun nativeVerify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean
    @JvmStatic external fun nativeRoomId(publicKey: ByteArray): String?
    @JvmStatic external fun nativeSealJoin(hostPublicKey: ByteArray, plaintext: ByteArray): ByteArray?
    @JvmStatic external fun nativeOpenJoin(seed: ByteArray, packet: ByteArray): ByteArray?
    @JvmStatic external fun nativeEncrypt(session: ByteArray, direction: Int, plaintext: ByteArray): ByteArray?
    @JvmStatic external fun nativeDecrypt(session: ByteArray, direction: Int, packet: ByteArray): ByteArray?
    @JvmStatic external fun nativeEngineOpen(gateway: String, networkHandle: Long): String?
    @JvmStatic external fun nativeEngineOpenDoor(gateway: String, networkHandle: Long): String?
    @JvmStatic external fun nativeEngineCloseDoor(slot: Int)
    @JvmStatic external fun nativeEngineSocket(): Int
    @JvmStatic external fun nativeEngineStart(tunFd: Int, isHost: Boolean, localAddress: String): Boolean
    @JvmStatic external fun nativeEngineDetach()
    @JvmStatic external fun nativeEngineAddPeer(
        publicKeyHex: String,
        address: String,
        candidates: String,
        initiator: Boolean,
        slot: Int
    ): Boolean
    @JvmStatic external fun nativeEngineRemovePeer(address: String)
    @JvmStatic external fun nativeEngineStatus(): String?
    @JvmStatic external fun nativeEngineClose()
}
