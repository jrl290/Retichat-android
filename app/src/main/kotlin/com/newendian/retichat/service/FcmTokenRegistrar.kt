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

    private var lastRegistrationKey: String? = null
    private var pendingRegistrationKey: String? = null

    private val stateLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Register the token with `fcm.register` via the ephemeral AppLink.
     * Fires one immediate attempt and leaves an ACTIVE-status handler
     * installed so later readiness can drive the same send without retries.
     * // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
     */
    suspend fun registerIfNeeded(context: Context, subscriberHash: ByteArray): Boolean {
        if (subscriberHash.size != 16) {
            Log.w(TAG, "registerIfNeeded: bad subscriberHash length ${subscriberHash.size}")
            return false
        }
        val rfedNodeHex = UserPreferences.getEffectiveRfedNodeIdentityHash(context)
        if (rfedNodeHex.length != 32) {
            Log.d(TAG, "No RFed node configured — skipping FCM registration")
            return false
        }
        val token = UserPreferences.getFcmDeviceToken(context)
        if (token.isEmpty()) {
            Log.d(TAG, "No FCM token yet — skipping registration")
            return false
        }
        val fcmRegisterDestHex = FcmBridgeHashes.registrationHex(context) ?: run {
            Log.i(TAG, "PushBridgeConfig.json missing or invalid — skipping FCM registration")
            return false
        }
        val destHash = hexToBytes(fcmRegisterDestHex) ?: return false

        val payload = encodeMsgpackRegistration(subscriberHash, token)
        val subscriberHashHex = subscriberHash.toHex()
        val registrationKey = subscriberHashHex + ":" + token

        if (!shouldAttempt(registrationKey)) {
            return isRegistered(registrationKey)
        }

        ConnectionStateManager.setAppLinkStatusHandler(destHash) { status ->
            if (status != RetichatBridge.AppLinkStatus.ACTIVE) return@setAppLinkStatusHandler
            scope.launch {
                attemptRegistrationIfNeeded(registrationKey, destHash, payload, subscriberHashHex)
            }
        }
        ConnectionStateManager.primeAppLink(destHash, "fcm", "register")
        return attemptRegistrationIfNeeded(registrationKey, destHash, payload, subscriberHashHex)
    }

    private suspend fun attemptRegistrationIfNeeded(
        key: String,
        destHash: ByteArray,
        payload: ByteArray,
        subscriberHashHex: String,
    ): Boolean {
        if (!shouldAttempt(key)) {
            return isRegistered(key)
        }
        markPending(key)
        val delivered = ConnectionStateManager.appLinkSendData(
            destHash, "fcm", "register", payload,
        )
        if (delivered) {
            Log.i(TAG, "FCM token registered for ${subscriberHashHex.take(8)}…")
            markRegistrationSucceeded(key)
            return true
        }
        Log.w(TAG, "Registration: no delivery proof within budget")
        clearPendingRegistration(key)
        return false
    }

    private fun shouldAttempt(key: String): Boolean = synchronized(stateLock) {
        lastRegistrationKey != key && pendingRegistrationKey != key
    }

    private fun isRegistered(key: String): Boolean = synchronized(stateLock) {
        lastRegistrationKey == key
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
