package com.retichat.app.service

import android.content.Context
import android.util.Log
import com.retichat.app.bridge.RetichatBridge
import kotlinx.coroutines.delay
import java.security.MessageDigest

/**
 * Registers this device's FCM registration token with the rfed FCM bridge
 * (rfed.fcm). Mirrors the iOS [ApnsTokenRegistrar] but speaks to a
 * Firebase Cloud Messaging bridge instead of APNs.
 *
 * Wire format (plain encrypted RNS packet to rfed.fcm):
 *   payload = msgpack fixmap-2 {
 *     "subscriber_hash": bin8(16),
 *     "fcm_token":       str(N)
 *   }
 *
 * Server-side, the rfed-fcm bridge keys subscribers by `subscriber_hash`
 * (= our lxmf.delivery dest hash) and drops a wakeup HTTP-v1 push at the
 * stored token whenever a new blob arrives for that subscriber.
 */
object FcmTokenRegistrar {

    private const val TAG = "FcmRegistrar"
    private const val MAX_ATTEMPTS = 8
    private const val BASE_DELAY_MS = 5_000L
    private const val MAX_DELAY_MS = 120_000L

    /**
     * Register the token with the rfed.fcm bridge. Suspends; safe to call
     * from a CoroutineWorker. Returns true on success, false if all
     * retries exhausted.
     */
    suspend fun registerIfNeeded(context: Context, subscriberHash: ByteArray): Boolean {
        if (subscriberHash.size != 16) {
            Log.w(TAG, "registerIfNeeded: bad subscriberHash length ${subscriberHash.size}")
            return false
        }
        val rfedNodeHex = UserPreferences.getRfedNodeIdentityHash(context)
        if (rfedNodeHex.length != 32) {
            Log.d(TAG, "No RFed node configured — skipping FCM registration")
            return false
        }
        val token = UserPreferences.getFcmDeviceToken(context)
        if (token.isEmpty()) {
            Log.d(TAG, "No FCM token yet — skipping registration")
            return false
        }
        val rfedFcmDestHex = rnsDestHash(rfedNodeHex, "rfed", listOf("fcm")) ?: return false
        val destHash = hexToBytes(rfedFcmDestHex) ?: return false

        val payload = encodeMsgpackRegistration(subscriberHash, token)

        var delayMs = BASE_DELAY_MS
        for (attempt in 1..MAX_ATTEMPTS) {
            if (!RetichatBridge.transportHasPath(destHash)) {
                RetichatBridge.transportRequestPath(destHash)
                Log.d(TAG, "Waiting for path (attempt $attempt)")
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(MAX_DELAY_MS)
                continue
            }
            val ok = RetichatBridge.packetSendToHash(destHash, "rfed", "fcm", payload)
            if (ok) {
                Log.i(TAG, "FCM token registered (attempt $attempt)")
                return true
            }
            Log.w(TAG, "Send failed (attempt $attempt): ${RetichatBridge.lastError()}")
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(MAX_DELAY_MS)
        }
        Log.w(TAG, "Giving up after $MAX_ATTEMPTS attempts")
        return false
    }

    // ── msgpack hand-rolled encoder ────────────────────────────────────

    private fun encodeMsgpackRegistration(subscriberHash: ByteArray, token: String): ByteArray {
        val out = ArrayList<Byte>(64 + token.length)
        out.add(0x82.toByte())                       // fixmap-2

        val k1 = "subscriber_hash".toByteArray()
        out.add((0xa0 or k1.size).toByte())          // fixstr
        out.addAll(k1.toList())
        out.add(0xc4.toByte())                       // bin8
        out.add(subscriberHash.size.toByte())
        out.addAll(subscriberHash.toList())

        val k2 = "fcm_token".toByteArray()
        out.add((0xa0 or k2.size).toByte())
        out.addAll(k2.toList())
        val tokBytes = token.toByteArray()
        when {
            tokBytes.size <= 0xFF -> {               // str8
                out.add(0xd9.toByte()); out.add(tokBytes.size.toByte())
            }
            tokBytes.size <= 0xFFFF -> {             // str16
                out.add(0xda.toByte())
                out.add(((tokBytes.size shr 8) and 0xff).toByte())
                out.add((tokBytes.size and 0xff).toByte())
            }
            else -> {                                // str32
                out.add(0xdb.toByte())
                out.add(((tokBytes.size shr 24) and 0xff).toByte())
                out.add(((tokBytes.size shr 16) and 0xff).toByte())
                out.add(((tokBytes.size shr 8) and 0xff).toByte())
                out.add((tokBytes.size and 0xff).toByte())
            }
        }
        out.addAll(tokBytes.toList())
        return out.toByteArray()
    }

    // ── helpers (also reused by RfedNotifyRegistrar / RfedChannelClient) ──

    /** Compute an RNS SINGLE-destination hash (mirrors `Destination::hash()`). */
    internal fun rnsDestHash(identityHashHex: String, app: String, aspects: List<String>): String? {
        val hex = identityHashHex.trim().lowercase()
        if (hex.length != 32) return null
        val identityBytes = hexToBytes(hex) ?: return null
        val name = (listOf(app) + aspects).joinToString(".")
        val md = MessageDigest.getInstance("SHA-256")
        val nameHash = md.digest(name.toByteArray()).copyOfRange(0, 10)
        val material = nameHash + identityBytes
        val full = MessageDigest.getInstance("SHA-256").digest(material)
        return full.copyOfRange(0, 16).joinToString("") { "%02x".format(it) }
    }

    internal fun hexToBytes(hex: String): ByteArray? {
        val clean = hex.trim().lowercase()
        if (clean.length % 2 != 0) return null
        return try {
            ByteArray(clean.length / 2) {
                ((Character.digit(clean[it * 2], 16) shl 4) or
                        Character.digit(clean[it * 2 + 1], 16)).toByte()
            }
        } catch (_: Exception) { null }
    }
}
