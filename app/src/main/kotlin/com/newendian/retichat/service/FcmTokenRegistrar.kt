package com.newendian.retichat.service

import android.content.Context
import android.util.Log
import com.newendian.retichat.bridge.RetichatBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Registers this device's FCM registration token with the FCM bridge's
 * canonical `fcm.register` destination. Mirrors the iOS [ApnsTokenRegistrar]
 * naming convention: wake packets land on `fcm.relay`, token upserts go to
 * `fcm.register`, and explicit removals target `fcm.unregister`.
 *
 * Protocol payload (msgpack Map sent as APP_LINK DATA):
 *   payload = msgpack fixmap-2 {
 *     "subscriber_hash": bin8(16),
 *     "fcm_token":       str(N)
 *   }
 *
 * The `fcm.register` destination hash is loaded from `PushBridgeConfig.json`
 * when available. Without it, FCM bridge registration is disabled.
 *
 * Server-side, the FCM bridge keys subscribers by `subscriber_hash`
 * (= our lxmf.delivery dest hash) and drops a wakeup HTTP-v1 push at the
 * stored token whenever a new blob arrives for that subscriber.
 */
object FcmTokenRegistrar {

    private const val TAG = "FcmRegistrar"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One per `fcm.register` destination (it changes with the bridge config). */
    private val registrations = HashMap<String, HeldLinkRegistrations>()

    /**
     * Owe the bridge this device's token: it is sent on a held link to
     * `fcm.register` once that link is established, with its delivery proof,
     * and owed until then (see [HeldLinkRegistrations]). A token already
     * registered in this process is not sent again.
     */
    suspend fun registerIfNeeded(context: Context, subscriberHash: ByteArray) {
        if (subscriberHash.size != 16) {
            Log.w(TAG, "registerIfNeeded: bad subscriberHash length ${subscriberHash.size}")
            return
        }
        val rfedNodeHex = UserPreferences.getEffectiveRfedNodeIdentityHash(context)
        if (rfedNodeHex.length != 32) {
            Log.d(TAG, "No RFed node configured — skipping FCM registration")
            return
        }
        val token = UserPreferences.getFcmDeviceToken(context)
        if (token.isEmpty()) {
            Log.d(TAG, "No FCM token yet — skipping registration")
            return
        }
        val fcmRegisterDestHex = FcmBridgeHashes.registrationHex(context) ?: run {
            Log.i(TAG, "PushBridgeConfig.json missing or invalid — skipping FCM registration")
            return
        }
        val destHash = hexToBytes(fcmRegisterDestHex) ?: return

        val payload = encodeMsgpackRegistration(subscriberHash, token)
        // The token itself stays out of the key, which is logged.
        val tokenTag = MessageDigest.getInstance("SHA-256").digest(token.toByteArray())
            .copyOfRange(0, 4).joinToString("") { "%02x".format(it) }
        val key = "token ${subscriberHash.toHex().take(8)}/$tokenTag"
        val registration = synchronized(registrations) {
            registrations.getOrPut(fcmRegisterDestHex) {
                HeldLinkRegistrations(destHash, "fcm", "register", scope)
            }
        }
        registration.owe(key, payload)
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

    private fun normalizedDestinationAspects(app: String, aspects: List<String>): List<String> {
        val normalizedApp = app.trim()
        val segments = aspects.flatMap { aspect ->
            aspect.split('.', ',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        return if (segments.firstOrNull() == normalizedApp) segments.drop(1) else segments
    }

    /** Compute an RNS SINGLE-destination hash (mirrors `Destination::hash()`). */
    internal fun rnsDestHash(identityHashHex: String, app: String, aspects: String): String? =
        rnsDestHash(identityHashHex, app, listOf(aspects))

    /** Compute an RNS SINGLE-destination hash (mirrors `Destination::hash()`). */
    internal fun rnsDestHash(identityHashHex: String, app: String, aspects: List<String>): String? {
        val hex = identityHashHex.trim().lowercase()
        if (hex.length != 32) return null
        val identityBytes = hexToBytes(hex) ?: return null
        val name = (listOf(app) + normalizedDestinationAspects(app, aspects)).joinToString(".")
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

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }
}
