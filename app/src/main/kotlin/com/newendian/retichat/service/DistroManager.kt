package com.newendian.retichat.service

import android.content.Context
import android.util.Log
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.service.DistroCodec.toHex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The device's copy of a distro identity: one LXMF identity shared by all of
 * a person's devices (RFed SPEC §17). Mirrors Retichat-js/lib/distro.js.
 *
 * The 64-byte private key is kept in the app's private files directory next
 * to the device identity (`filesDir/identity`), which is the protection the
 * app already gives its main key. The web client keeps it in localStorage,
 * iOS in the Keychain. The key is never shown in the UI; it moves to another
 * device only through the LXMF transfer message (field 0x0D).
 *
 * When a distro is loaded it is the identity the app sends from and the
 * address it shows, exactly as the web client's `sendingIdentity()` does.
 * The device identity keeps owning the network layer (lxmf.delivery inbound,
 * rfed.delivery, link identification); the distro's own lxmf.delivery is
 * never registered inbound here — RFed announces it on our behalf.
 */
object DistroManager {
    private const val TAG = "Distro"
    private const val FILE_NAME = "distro_identity"

    data class DistroState(
        val deliveryHashHex: String,
        val publicKeyHex: String,
        val identityHashHex: String,
    ) {
        val contactUri: String get() = DistroCodec.contactUri(deliveryHashHex, publicKeyHex)
    }

    @Volatile var identityHandle: Long = 0L
        private set
    @Volatile var deliveryHash: ByteArray? = null
        private set

    private val _state = MutableStateFlow<DistroState?>(null)
    /** Null when no distro identity is loaded. */
    val state: StateFlow<DistroState?> = _state

    val hasDistro: Boolean get() = identityHandle != 0L
    val deliveryHashHex: String? get() = deliveryHash?.toHex()

    /** Load the stored key, if any. Safe to call more than once. */
    @Synchronized
    fun init(context: Context) {
        if (identityHandle != 0L) return
        val f = file(context)
        if (!f.isFile) return
        val key = runCatching { f.readBytes() }.getOrNull()
        if (key == null || key.size != DistroCodec.PRIVATE_KEY_BYTES) {
            Log.w(TAG, "stored distro key unreadable (${key?.size} bytes) — ignoring")
            return
        }
        if (!adopt(key)) {
            Log.w(TAG, "stored distro key rejected by the core: ${RetichatBridge.lastError()}")
        }
    }

    /** Create a fresh distro identity, persist it and load it. */
    @Synchronized
    fun generate(context: Context): Boolean {
        val key = RetichatBridge.distroGenerate() ?: run {
            Log.e(TAG, "generate failed: ${RetichatBridge.lastError()}")
            return false
        }
        return importKey(context, key)
    }

    /** Import from a transfer URI, the legacy URI or bare 128-hex. */
    @Synchronized
    fun importText(context: Context, text: String): Boolean {
        val key = DistroCodec.parsePrivateKey(text) ?: return false
        return importKey(context, key)
    }

    @Synchronized
    fun importKey(context: Context, key: ByteArray): Boolean {
        if (key.size != DistroCodec.PRIVATE_KEY_BYTES) return false
        val previous = identityHandle
        if (!adopt(key)) return false
        runCatching { file(context).writeBytes(key) }
            .onFailure { Log.e(TAG, "could not persist distro key", it) }
        if (previous != 0L && previous != identityHandle) RetichatBridge.identityDestroy(previous)
        return true
    }

    /** The 128-hex private key, for the device-to-device transfer only. */
    fun exportHex(): String? {
        val h = identityHandle
        if (h == 0L) return null
        return RetichatBridge.distroPrivateKey(h)?.toHex()
    }

    /** Drop the distro identity from this device (the caller unregisters first). */
    @Synchronized
    fun forget(context: Context) {
        val h = identityHandle
        identityHandle = 0L
        deliveryHash = null
        _state.value = null
        if (h != 0L) RetichatBridge.identityDestroy(h)
        file(context).delete()
        Log.i(TAG, "distro identity forgotten")
    }

    /**
     * `(sourceHash, signingIdentityHandle)` for an outgoing message: the
     * distro when one is loaded, otherwise the device.
     */
    fun sendingIdentity(deviceHash: ByteArray, deviceHandle: Long): Pair<ByteArray, Long> {
        val h = identityHandle
        val d = deliveryHash
        return if (h != 0L && d != null) d to h else deviceHash to deviceHandle
    }

    private fun adopt(key: ByteArray): Boolean {
        val handle = RetichatBridge.identityFromBytes(key)
        if (handle == 0L) return false
        val delivery = RetichatBridge.distroDeliveryHash(handle)
        val pub = RetichatBridge.identityPublicKey(handle)
        val idHash = RetichatBridge.identityHash(handle)
        if (delivery == null || pub == null || idHash == null) {
            RetichatBridge.identityDestroy(handle)
            return false
        }
        identityHandle = handle
        deliveryHash = delivery
        _state.value = DistroState(delivery.toHex(), pub.toHex(), idHash.toHex())
        Log.i(TAG, "distro loaded: address=${delivery.toHex()}")
        return true
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)
}
