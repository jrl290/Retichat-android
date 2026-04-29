package com.retichat.app.service

import android.content.Context
import android.util.Log
import com.retichat.app.bridge.RetichatBridge
import kotlinx.coroutines.delay

/**
 * Registers this device's FCM "wakeup" relay with rfed via a Link request
 * to `/rfed/notify/register`. Mirrors iOS [RfedNotifyRegistrar].
 *
 * The `relayHex` we send is the rfed-fcm bridge's own rfed.notify
 * destination hash (32 hex chars). The rfed node uses it as the routing
 * target for the wakeup message that ultimately translates into an FCM
 * data message arriving on this device.
 *
 * Payload signed-array layout (matches iOS verbatim):
 *   value = msgpack fixarray-2 [str(relay_hex), bin(16 channel_hash) | nil]
 *   wire  = msgpack fixarray-3 [bin(value), bin(64) pubkey, bin(64) sig]
 * where sig = Ed25519(value).
 */
object RfedNotifyRegistrar {

    private const val TAG = "RfedNotify"
    private const val MAX_ATTEMPTS = 8
    private const val BASE_DELAY_MS = 5_000L
    private const val MAX_DELAY_MS = 120_000L

    /** Register the LXMF wakeup (no per-channel scope). */
    suspend fun registerIfNeeded(context: Context, identityHandle: Long): Boolean {
        return doRegister(context, identityHandle, channelHash = null, unregister = false)
    }

    /** Register a per-channel wakeup so the rfed node pings on activity in [channelHash]. */
    suspend fun registerForChannel(
        context: Context,
        identityHandle: Long,
        channelHash: ByteArray,
    ): Boolean = doRegister(context, identityHandle, channelHash, unregister = false)

    /** Unregister a per-channel wakeup (best-effort, single attempt, no retry). */
    suspend fun deregisterForChannel(
        context: Context,
        identityHandle: Long,
        channelHash: ByteArray,
    ): Boolean = doRegister(context, identityHandle, channelHash, unregister = true)

    /** Best-effort unregister from an old rfed node before switching. */
    suspend fun deregisterFrom(
        context: Context,
        identityHandle: Long,
        oldRfedNotifyHashHex: String,
    ): Boolean {
        val rfedHash = FcmTokenRegistrar.hexToBytes(oldRfedNotifyHashHex) ?: return false
        val relayHex = relayHexOrNull(context) ?: return false
        val payload = buildSignedPayload(identityHandle, relayHex, channelHash = null) ?: return false
        if (!RetichatBridge.transportHasPath(rfedHash)) return false
        val resp = RetichatBridge.linkRequest(
            rfedHash, "rfed", "notify", identityHandle,
            "/rfed/notify/unregister", payload, timeoutSecs = 5.0,
        )
        Log.i(TAG, "Sent unregister to old rfed node (resp=${resp != null})")
        return resp != null
    }

    // ---- Internal ----

    private suspend fun doRegister(
        context: Context,
        identityHandle: Long,
        channelHash: ByteArray?,
        unregister: Boolean,
    ): Boolean {
        val rfedNotifyHex = FcmTokenRegistrar.rnsDestHash(
            UserPreferences.getRfedNodeIdentityHash(context), "rfed", listOf("notify"),
        ) ?: return false
        val rfedHash = FcmTokenRegistrar.hexToBytes(rfedNotifyHex) ?: return false
        val relayHex = relayHexOrNull(context) ?: return false
        val payload = buildSignedPayload(identityHandle, relayHex, channelHash) ?: return false
        val path = if (unregister) "/rfed/notify/unregister" else "/rfed/notify/register"

        var delayMs = BASE_DELAY_MS
        for (attempt in 1..MAX_ATTEMPTS) {
            if (!RetichatBridge.transportHasPath(rfedHash)) {
                RetichatBridge.transportRequestPath(rfedHash)
                Log.d(TAG, "Waiting for path (attempt $attempt)")
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_DELAY_MS)
                continue
            }
            val resp = RetichatBridge.linkRequest(
                rfedHash, "rfed", "notify", identityHandle, path, payload, timeoutSecs = 15.0,
            )
            if (resp != null) {
                val ok = resp.size == 1 && resp[0] == 0xc3.toByte()
                if (ok) {
                    Log.i(TAG, "$path OK (attempt $attempt)")
                    return true
                }
                Log.w(TAG, "$path returned non-true (attempt $attempt)")
            } else {
                Log.w(TAG, "$path link request failed: ${RetichatBridge.lastError()}")
                RetichatBridge.transportRequestPath(rfedHash)
            }
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(MAX_DELAY_MS)
        }
        Log.w(TAG, "Giving up after $MAX_ATTEMPTS attempts")
        return false
    }

    /**
     * The relay hash is the rfed-fcm bridge's own rfed.notify destination
     * hash. We derive it from the same RFed node identity as everything
     * else — `relay = sha256("rfed.notify"[..10] | rfedNodeIdentity)[..16]`.
     * In the iOS build this comes from `PushBridgeConfig.plist`; here we
     * just compute it deterministically from the configured RFed node.
     */
    private fun relayHexOrNull(context: Context): String? {
        val rfedNodeHex = UserPreferences.getRfedNodeIdentityHash(context)
        if (rfedNodeHex.length != 32) return null
        return FcmTokenRegistrar.rnsDestHash(rfedNodeHex, "rfed", listOf("notify"))
    }

    /**
     * Build the msgpack-3 signed payload:
     *   value = fixarray-2 [str(relayHex), bin(16) channelHash | nil]
     *   wire  = fixarray-3 [bin(value), bin(64) pubkey, bin(64) sig]
     */
    private fun buildSignedPayload(
        identityHandle: Long,
        relayHex: String,
        channelHash: ByteArray?,
    ): ByteArray? {
        val value = ArrayList<Byte>(80)
        value.add(0x92.toByte())                     // fixarray-2
        // str(relayHex)
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
            // bin8 if fits; bin16 otherwise
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
}
