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

    /** Last rfed.notify registration tuple successfully accepted this run. */
    private var lastRegistrationKey: String? = null

    /** Registration tuple currently in flight. */
    private var pendingRegistrationKey: String? = null

    private val stateLock = Any()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Register the LXMF wakeup once the rfed.notify APP_LINK reaches ACTIVE.
     * Idempotent — additional calls within one process lifetime are no-ops.
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
        val registrationKey = rfedHash.toHex() + ":" + relayHex + ":" + identityHandle
        if (!shouldAttempt(registrationKey)) return

        scope.launch {
            ConnectionStateManager.setAppLinkStatusHandler(rfedHash) { status ->
                if (status != RetichatBridge.AppLinkStatus.ACTIVE) return@setAppLinkStatusHandler
                scope.launch {
                    attemptRegistrationIfNeeded(registrationKey, rfedHash, payload, "register")
                }
            }
            ConnectionStateManager.primeAppLink(rfedHash, "rfed", "notify.register")
            attemptRegistrationIfNeeded(registrationKey, rfedHash, payload, "register")
        }
    }

    /** Per-channel registration — best-effort single attempt. */
    fun registerForChannel(context: Context, identityHandle: Long, channelHash: ByteArray) {
        val rfedHash = rfedNotifyDestHash(context, "register") ?: return
        val relayHex = relayHexOrNull(context) ?: return
        val payload = buildSignedPayload(identityHandle, "register", relayHex, channelHash) ?: return
        scope.launch {
            val delivered = ConnectionStateManager.appLinkSendData(
                rfedHash, "rfed", "notify.register", payload,
            )
            logSendResult("channel-register", delivered)
        }
    }

    /** Per-channel deregistration — best-effort single attempt. */
    fun deregisterForChannel(context: Context, identityHandle: Long, channelHash: ByteArray) {
        val rfedHash = rfedNotifyDestHash(context, "unregister") ?: return
        val relayHex = relayHexOrNull(context) ?: return
        val payload = buildSignedPayload(identityHandle, "unregister", relayHex, channelHash) ?: return
        scope.launch {
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

    private suspend fun attemptRegistrationIfNeeded(
        key: String,
        rfedHash: ByteArray,
        payload: ByteArray,
        kind: String,
    ) {
        if (!shouldAttempt(key)) return
        markPending(key)
        val delivered = ConnectionStateManager.appLinkSendData(
            rfedHash, "rfed", "notify.register", payload,
        )
        logSendResult(kind, delivered)
        if (delivered) {
            markRegistrationSucceeded(key)
        } else {
            clearPendingRegistration(key)
        }
    }

    private fun logSendResult(kind: String, delivered: Boolean) {
        if (delivered) Log.i(TAG, "$kind: delivered to rfed.notify")
        else Log.w(TAG, "$kind: no delivery proof within budget")
    }

    private fun shouldAttempt(key: String): Boolean = synchronized(stateLock) {
        lastRegistrationKey != key && pendingRegistrationKey != key
    }

    private fun markPending(key: String) = synchronized(stateLock) {
        pendingRegistrationKey = key
    }

    private fun clearPendingRegistration(key: String) = synchronized(stateLock) {
        if (pendingRegistrationKey == key) {
            pendingRegistrationKey = null
        }
    }

    private fun markRegistrationSucceeded(key: String) = synchronized(stateLock) {
        lastRegistrationKey = key
        if (pendingRegistrationKey == key) {
            pendingRegistrationKey = null
        }
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

