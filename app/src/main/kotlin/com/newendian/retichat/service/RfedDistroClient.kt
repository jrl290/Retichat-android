package com.newendian.retichat.service

import android.content.Context
import android.util.Log
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.LxmfFields
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.service.DistroCodec.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The device's conversation with RFed about its distro (RFed SPEC §17),
 * mirroring Retichat-js app.js `_registerDistro`, `_publishDistroAnnounce`,
 * `_unregisterDistro`, `_pullDistroMessages` and `_handleDistroBlob`.
 *
 * Android talks to the split-aspect destinations the node still serves
 * (`rfed.distro.register` carries /rfed/distro/register, /rfed/distro/announce
 * and /rfed/pull; `rfed.distro.unregister` carries /rfed/distro/unregister),
 * over the same AppLink machinery the channel and notify clients use.
 *
 * No retry loops (DESIGN_PRINCIPLES §3): a failed registration is re-tried
 * once when the destination's AppLink next becomes ACTIVE, the same shape as
 * RfedNotifyRegistrar.
 */
object RfedDistroClient {
    private const val TAG = "Distro"
    private const val PULL_ROUNDS_MAX = 8
    private const val PULL_AFTER_REGISTER_MS = 7_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class Status(
        val registered: Boolean = false,
        val announced: Boolean = false,
        val lastError: String? = null,
        val lastPullCount: Int = 0,
    )

    /** A distro private key another device sent us (LXMF field 0x0D). */
    data class TransferOffer(val fromHashHex: String, val privateKeyHex: String)

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status
    private val _pendingTransfer = MutableStateFlow<TransferOffer?>(null)
    /** Shown by the UI as an "import this distro identity?" prompt. */
    val pendingTransfer: StateFlow<TransferOffer?> = _pendingTransfer

    @Volatile private var registerInFlight = false
    @Volatile private var registeredForHandle: Long = 0L
    private val statusHandlerArmed = mutableSetOf<String>()

    /** The stack went down: the next start registers again (the node may differ). */
    fun onStackStopped() {
        registeredForHandle = 0L
        _status.value = Status()
    }

    /** Called at stack start and after generate/import. No-op without a distro. */
    fun registerIfNeeded(context: Context) {
        val distro = DistroManager.identityHandle
        if (distro == 0L) return
        if (registeredForHandle == distro) return
        scope.launch { register(context) }
    }

    /** Register this device for the loaded distro, then publish its announce. */
    suspend fun register(context: Context): Boolean = withContext(Dispatchers.IO) {
        val distro = DistroManager.identityHandle
        val device = StackRuntime.identityHandle
        if (distro == 0L || device == 0L) return@withContext false
        if (registerInFlight) return@withContext registeredForHandle == distro
        registerInFlight = true
        try {
            val dest = destHash(context, "register") ?: return@withContext fail("no RFed node configured")
            val payload = RetichatBridge.distroRegisterPayload(device, distro)
                ?: return@withContext fail("register payload: ${RetichatBridge.lastError()}")
            ConnectionStateManager.primeAppLink(dest, "rfed", "distro.register")
            val resp = ConnectionStateManager.appLinkSend(dest, "rfed", "distro.register", "/rfed/distro/register", payload)
            if (!DistroCodec.isAffirmative(resp)) {
                armRetryOnActive(context, dest)
                return@withContext fail(if (resp == null) "RFed link not reachable" else "RFed refused registration")
            }
            registeredForHandle = distro
            _status.value = _status.value.copy(registered = true, lastError = null)
            Log.i(TAG, "registered device with RFed (distro=${DistroManager.deliveryHashHex})")
            publishAnnounce(context, dest, distro)
            scope.launch {
                delay(PULL_AFTER_REGISTER_MS)
                pull(context)
            }
            true
        } finally {
            registerInFlight = false
        }
    }

    private suspend fun publishAnnounce(context: Context, dest: ByteArray, distro: Long) {
        val payload = RetichatBridge.distroAnnouncePayload(distro, null) ?: run {
            Log.w(TAG, "announce payload: ${RetichatBridge.lastError()}")
            return
        }
        val resp = ConnectionStateManager.appLinkSend(dest, "rfed", "distro.register", "/rfed/distro/announce", payload)
        if (DistroCodec.isAffirmative(resp)) {
            _status.value = _status.value.copy(announced = true)
            Log.i(TAG, "RFed is now announcing the distro address")
        } else {
            _status.value = _status.value.copy(announced = false, lastError = "RFed refused the pre-signed announce")
            Log.w(TAG, "RFed refused the pre-signed announce (resp=${resp?.size} bytes)")
        }
    }

    /** Tell RFed this device no longer holds the distro (before forgetting it). */
    suspend fun unregister(context: Context): Boolean = withContext(Dispatchers.IO) {
        val distro = DistroManager.identityHandle
        val device = StackRuntime.identityHandle
        if (distro == 0L || device == 0L) return@withContext false
        val dest = destHash(context, "unregister") ?: return@withContext false
        val payload = RetichatBridge.distroRegisterPayload(device, distro) ?: return@withContext false
        val resp = ConnectionStateManager.appLinkSend(dest, "rfed", "distro.unregister", "/rfed/distro/unregister", payload)
        registeredForHandle = 0L
        _status.value = Status()
        DistroCodec.isAffirmative(resp)
    }

    /** Drain fan-out copies RFed held for this device while it was unreachable. */
    suspend fun pull(context: Context): Int = withContext(Dispatchers.IO) {
        if (DistroManager.identityHandle == 0L) return@withContext 0
        val dest = destHash(context, "register") ?: return@withContext 0
        var total = 0
        for (round in 1..PULL_ROUNDS_MAX) {
            val resp = ConnectionStateManager.appLinkSend(dest, "rfed", "distro.register", "/rfed/pull", DistroCodec.MSGPACK_NIL)
                ?: run { Log.w(TAG, "pull: request failed"); return@withContext total }
            val (pairs, more) = DistroCodec.decodePullResponse(resp) ?: run {
                Log.w(TAG, "pull: malformed response (${resp.size} bytes)")
                return@withContext total
            }
            for ((_, blob) in pairs) {
                handleBlob(context, blob)
                total++
            }
            _status.value = _status.value.copy(lastPullCount = total)
            if (!more) break
        }
        Log.i(TAG, "pull drained $total blob(s)")
        total
    }

    /**
     * One fanned-out copy, without the rfed.delivery prefix: `dest(16) |
     * encrypted`. Decrypt with the distro key, dedupe on source+timestamp,
     * then hand it to the repository as an inbound DM, or, when it is a
     * sibling's §17.11 sent-copy, as an outgoing message in the recipient's chat.
     */
    suspend fun handleBlob(context: Context, blob: ByteArray) {
        val distro = DistroManager.identityHandle
        if (distro == 0L) return
        val json = RetichatBridge.distroUnwrap(distro, blob)
        if (json == null) {
            Log.w(TAG, "blob rejected: ${RetichatBridge.lastError()}")
            return
        }
        if (json.isEmpty()) {
            Log.i(TAG, "blob for a different distro — ignored")
            return
        }
        val o = JSONObject(json)
        val srcHex = o.getString("source_hash")
        val timestamp = o.getDouble("timestamp")
        val content = o.optString("content", "")
        val title = o.optString("title", "")
        val transferKey = o.optString("distro_transfer_key", "").takeIf { it.isNotEmpty() && !o.isNull("distro_transfer_key") }
        val isNotification = o.optBoolean("is_delivery_notification", false)
        // RFed SPEC §17.11 sent-copy marker (lxmf_rust::distro::DistroMessage
        // sent_to / sent_by). org.json's optString turns null into "null", so
        // read null explicitly; an empty sent_by is still a marker.
        val sentTo = if (o.isNull("sent_to")) null else o.optString("sent_to")
        val sentBy = if (o.isNull("sent_by")) null else o.optString("sent_by")

        val key = DistroCodec.seenKey(srcHex, timestamp)
        if (!UserPreferences.markDistroSeen(context, key)) {
            Log.i(TAG, "duplicate fan-out copy from ${srcHex.take(8)} — skipped")
            return
        }
        if (transferKey != null) {
            offerTransfer(srcHex, transferKey)
            return
        }
        if (isNotification) return
        val app = context.applicationContext as? RetichatApp ?: return
        // A message a sibling device sent as the distro (RFed SPEC §17.11
        // receiver rules; the iOS counterpart is RfedDistroClient.swift).
        // Checked after the seen-mark so our own echo is still recorded.
        when (val copy = DistroCodec.classifySentCopy(
            sourceHex = srcHex,
            ownDistroHex = DistroManager.deliveryHashHex,
            ownDeviceHex = StackRuntime.selfDestHash.toHex(),
            sentTo = sentTo,
            sentBy = sentBy,
        )) {
            DistroCodec.SentCopy.NotACopy -> Unit
            DistroCodec.SentCopy.OwnEcho -> return
            DistroCodec.SentCopy.Foreign -> {
                Log.w(TAG, "sent-copy marker from ${srcHex.take(8)}, not our distro — ignored")
                return
            }
            is DistroCodec.SentCopy.Malformed -> {
                Log.w(TAG, "sent-copy with unusable recipient '${copy.sentTo}' — dropped")
                return
            }
            is DistroCodec.SentCopy.Store -> {
                app.repository.onDistroSentCopy(copy.recipientHex, title, content, timestamp)
                return
            }
        }
        val srcHash = DistroCodec.hexToBytes(srcHex) ?: return
        app.repository.onDistroMessageReceived(srcHash, title, content, timestamp)
    }

    /** Another device offered us a distro key (field 0x0D or a distro blob). */
    fun offerTransfer(fromHashHex: String, privateKeyHex: String) {
        if (DistroCodec.parsePrivateKey(privateKeyHex) == null) {
            Log.w(TAG, "transfer from ${fromHashHex.take(8)} carried an invalid key — ignored")
            return
        }
        Log.i(TAG, "distro identity offered by ${fromHashHex.take(8)}")
        _pendingTransfer.value = TransferOffer(fromHashHex, privateKeyHex)
    }

    /** Accept (import + register) or decline the pending transfer. */
    fun resolveTransfer(context: Context, accept: Boolean) {
        val offer = _pendingTransfer.value ?: return
        _pendingTransfer.value = null
        if (!accept) return
        if (DistroManager.importText(context, offer.privateKeyHex)) {
            registeredForHandle = 0L
            registerIfNeeded(context)
        }
    }

    /**
     * Send our distro key to another of our devices, as the web client's
     * `_sendDistroViaLxmf` does: an LXMF message signed by the DEVICE
     * identity with the key in field 0x0D. Direct first, propagation copy if
     * the direct send is not accepted.
     */
    suspend fun sendIdentityTo(context: Context, deviceHashHex: String): Boolean = withContext(Dispatchers.IO) {
        val keyHex = DistroManager.exportHex() ?: return@withContext false
        val dest = DistroCodec.hexToBytes(deviceHashHex.trim().lowercase()) ?: return@withContext false
        if (dest.size != 16) return@withContext false
        val router = StackRuntime.routerHandle
        val device = StackRuntime.identityHandle
        val selfHash = StackRuntime.selfDestHash
        if (router == 0L || device == 0L || selfHash.isEmpty()) return@withContext false
        RetichatBridge.appLinkOpen(router, dest, "lxmf", "delivery")
        for (method in listOf(RetichatBridge.DeliveryMethod.DIRECT, RetichatBridge.DeliveryMethod.PROPAGATED)) {
            val h = RetichatBridge.messageCreate(
                destHash = dest, srcHash = selfHash,
                content = "Distro identity transfer", title = "Distro Identity",
                method = method, identityHandle = device,
            )
            if (h == 0L) { Log.w(TAG, "transfer: messageCreate failed: ${RetichatBridge.lastError()}"); continue }
            RetichatBridge.messageAddFieldString(h, LxmfFields.FIELD_CUSTOM_TYPE, LxmfFields.DISTRO_TRANSFER_TYPE)
            RetichatBridge.messageAddFieldString(h, LxmfFields.FIELD_CUSTOM_DATA, keyHex)
            val sent = RetichatBridge.messageSendViaAppLinks(h)
            if (sent) {
                Log.i(TAG, "distro identity sent to ${deviceHashHex.take(8)} ($method)")
                return@withContext true
            }
            RetichatBridge.messageDestroy(h)
        }
        false
    }

    private fun armRetryOnActive(context: Context, dest: ByteArray) {
        val key = dest.toHex()
        synchronized(statusHandlerArmed) {
            if (!statusHandlerArmed.add(key)) return
        }
        scope.launch {
            ConnectionStateManager.setAppLinkStatusHandler(dest) { status ->
                if (status != RetichatBridge.AppLinkStatus.ACTIVE) return@setAppLinkStatusHandler
                synchronized(statusHandlerArmed) { statusHandlerArmed.remove(key) }
                scope.launch { register(context) }
            }
        }
    }

    private fun destHash(context: Context, op: String): ByteArray? {
        val rfedId = UserPreferences.getEffectiveRfedNodeIdentityHash(context)
        return FcmTokenRegistrar.rnsDestHash(rfedId, "rfed", listOf("distro", op))
            ?.let { FcmTokenRegistrar.hexToBytes(it) }
    }

    private fun fail(reason: String): Boolean {
        Log.w(TAG, "registration: $reason")
        _status.value = _status.value.copy(registered = false, lastError = reason)
        return false
    }
}
