package com.newendian.retichat.service

import android.content.Context
import android.util.Log
import com.newendian.retichat.bridge.RetichatBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Registers this device's FCM "wakeup" relay with rfed via a signed DATA
 * packet over the ephemeral `rfed.notify.register` / `rfed.notify.unregister`
 * APP_LINKs (split aspects — REFACTOR.md step 6).
 *
 * Direct port of `Retichat-ios/Retichat/Services/RfedNotifyRegistrar.swift`.
 *
 * The `relayHex` we send is the rfed-fcm bridge's own rfed.notify
 * destination hash (32 hex chars), loaded from `PushBridgeConfig.json`.
 *
 * Payload signed-array layout (matches iOS verbatim):
 *   value = msgpack fixarray-3 [str(op), str(relay_hex)|nil, bin(16 channel_hash) | nil]
 *   wire  = msgpack fixarray-3 [bin(value), bin(64) pubkey, bin(64) sig]
 *
 * Driven by the rfed.notify.register APP_LINK status callback so we only fire
 * once the persistent link is ACTIVE — never on a 5 s cold-start timeout, and
 * never with app-level retries.  See DESIGN_PRINCIPLES.md §1, §2, §3.
 */
object RfedNotifyRegistrar {

    private const val TAG = "RfedNotify"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One per `rfed.notify.register` destination (it changes with the node). */
    private val registrations = HashMap<String, HeldLinkRegistrations>()

    private fun registrationsFor(rfedHash: ByteArray): HeldLinkRegistrations =
        synchronized(registrations) {
            registrations.getOrPut(rfedHash.toHex()) {
                HeldLinkRegistrations(rfedHash, "rfed", "notify.register", scope)
            }
        }

    /**
     * Owe the node this device's LXMF wake registration: sent on a held link
     * to `rfed.notify.register` once it is established, with its delivery
     * proof, and owed until then (see [HeldLinkRegistrations]). Already
     * registered in this process: nothing is sent.
     */
    fun registerIfNeeded(context: Context, identityHandle: Long) {
        val rfedHash = rfedNotifyDestHash(context, "register") ?: return
        val relayHex = relayHexOrNull(context) ?: run {
            Log.i(TAG, "PushBridgeConfig.json missing or invalid — skipping register")
            return
        }
        val payload = buildSignedPayload(identityHandle, "register", relayHex, channelHash = null) ?: run {
            Log.w(TAG, "Failed to sign payload")
            return
        }
        val key = "lxmf relay=${relayHex.take(8)} identity=$identityHandle"
        scope.launch { registrationsFor(rfedHash).owe(key, payload) }
    }

    /** Per-channel registration: owed like [registerIfNeeded]. */
    fun registerForChannel(context: Context, identityHandle: Long, channelHash: ByteArray) {
        val rfedHash = rfedNotifyDestHash(context, "register") ?: return
        val relayHex = relayHexOrNull(context) ?: return
        val payload = buildSignedPayload(identityHandle, "register", relayHex, channelHash) ?: return
        scope.launch { registrationsFor(rfedHash).owe(channelKey(channelHash, relayHex, identityHandle), payload) }
    }

    private fun channelKey(channelHash: ByteArray, relayHex: String, identityHandle: Long): String =
        "channel ${channelHash.toHex().take(8)} relay=${relayHex.take(8)} identity=$identityHandle"

    /** Per-channel deregistration — best-effort single attempt. */
    fun deregisterForChannel(context: Context, identityHandle: Long, channelHash: ByteArray) {
        val rfedHash = rfedNotifyDestHash(context, "unregister") ?: return
        val relayHex = relayHexOrNull(context) ?: return
        val payload = buildSignedPayload(identityHandle, "unregister", relayHex, channelHash) ?: return
        val registerHash = rfedNotifyDestHash(context, "register")
        scope.launch {
            // A registration still owed must not go out after this.
            registerHash?.let { registrationsFor(it).forget(channelKey(channelHash, relayHex, identityHandle)) }
            val delivered = ConnectionStateManager.appLinkSendData(
                rfedHash, "rfed", "notify.unregister", payload,
            )
            logSendResult("channel-unregister", delivered)
        }
    }

    /** Best-effort unregister from an old rfed node before switching nodes. */
    fun deregisterFrom(context: Context, identityHandle: Long, oldRfedNotifyHashHex: String) {
        if (oldRfedNotifyHashHex.isEmpty()) return
        val rfedHash = FcmTokenRegistrar.hexToBytes(oldRfedNotifyHashHex) ?: return
        val relayHex = relayHexOrNull(context) ?: return
        val payload = buildSignedPayload(identityHandle, "unregister", relayHex, channelHash = null) ?: return
        scope.launch {
            val delivered = ConnectionStateManager.appLinkSendData(
                rfedHash, "rfed", "notify.unregister", payload,
            )
            logSendResult("unregister-old", delivered)
        }
    }

    // ---- Internal ----

    /// Single-attempt registration via a signed DATA packet on the ephemeral
    /// rfed.notify.{register,unregister} APP_LINK.
    /// // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
    private suspend fun sendOnce(rfedHash: ByteArray, payload: ByteArray, kind: String, op: String) {
        val delivered = ConnectionStateManager.appLinkSendData(
            rfedHash, "rfed", "notify.$op", payload,
        )
        logSendResult(kind, delivered)
    }

    private fun logSendResult(kind: String, delivered: Boolean) {
        if (delivered) Log.i(TAG, "$kind: delivered to rfed.notify")
        else Log.w(TAG, "$kind: no delivery proof within budget")
    }

    private fun rfedNotifyDestHash(context: Context, op: String): ByteArray? {
        val identityHex = UserPreferences.getEffectiveRfedNodeIdentityHash(context)
        if (identityHex.isEmpty()) return null
        val rfedNotifyHex = FcmTokenRegistrar.rnsDestHash(identityHex, "rfed", listOf("notify", op))
            ?: return null
        return FcmTokenRegistrar.hexToBytes(rfedNotifyHex)
    }

    private fun relayHexOrNull(context: Context): String? {
        return FcmBridgeHashes.relayHex(context)
    }

    /**
     * Build the msgpack-3 signed payload:
     *   value = fixarray-3 [str(op), str(relayHex)|nil, bin(16) channelHash | nil]
     *   wire  = fixarray-3 [bin(value), bin(64) pubkey, bin(64) sig]
     */
    private fun buildSignedPayload(
        identityHandle: Long,
        operation: String,
        relayHex: String?,
        channelHash: ByteArray?,
    ): ByteArray? {
        val value = ArrayList<Byte>(80)
        value.add(0x93.toByte())                     // fixarray-3
        val opBytes = operation.toByteArray()
        value.add((0xa0 or opBytes.size).toByte())
        value.addAll(opBytes.toList())
        if (relayHex != null) {
            val relayBytes = relayHex.toByteArray()
            when {
                relayBytes.size <= 31 -> value.add((0xa0 or relayBytes.size).toByte())
                relayBytes.size <= 0xff -> {
                    value.add(0xd9.toByte())
                    value.add(relayBytes.size.toByte())
                }
                else -> {
                    value.add(0xda.toByte())
                    value.add(((relayBytes.size shr 8) and 0xff).toByte())
                    value.add((relayBytes.size and 0xff).toByte())
                }
            }
            value.addAll(relayBytes.toList())
        } else {
            value.add(0xc0.toByte())
        }
        if (channelHash != null) {
            value.add(0xc4.toByte())
            value.add(channelHash.size.toByte())
            value.addAll(channelHash.toList())
        } else {
            value.add(0xc0.toByte())                 // nil
        }
        val valueBytes = value.toByteArray()
        val pubkey = RetichatBridge.identityPublicKey(identityHandle) ?: return null
        val sig = RetichatBridge.identitySign(identityHandle, valueBytes) ?: return null
        return msgpackFixarray3Bin(valueBytes, pubkey, sig)
    }

    private fun msgpackFixarray3Bin(a: ByteArray, b: ByteArray, c: ByteArray): ByteArray {
        val out = ArrayList<Byte>(3 + 2 * 3 + a.size + b.size + c.size)
        out.add(0x93.toByte())
        for (item in listOf(a, b, c)) {
            if (item.size <= 0xff) {
                out.add(0xc4.toByte()); out.add(item.size.toByte())
            } else {
                out.add(0xc5.toByte())
                out.add(((item.size shr 8) and 0xff).toByte())
                out.add((item.size and 0xff).toByte())
            }
            out.addAll(item.toList())
        }
        return out.toByteArray()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}

