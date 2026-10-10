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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.newendian.retichat.names.NameField
import com.newendian.retichat.names.Signature
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
    // Its launches pull and handle distro blobs (register's pull): what escapes
    // one is logged, never the process's end (RemoteInput.kt).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + logEscapedExceptions(TAG))

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
                // No answer is not a refusal (the truth rule): until 2026-10-10 a
                // request that got no response read "RFed link not reachable".
                return@withContext fail(DistroCodec.notAYes("registration", resp))
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

    /**
     * The Announce Display Name changed: hand RFed a new pre-signed distro
     * announce carrying it (DISPLAY_NAMES.md §2.2). Nothing to do without a
     * registered distro; registration publishes the current name itself.
     */
    fun republishAnnounce(context: Context) {
        val distro = DistroManager.identityHandle
        if (distro == 0L || registeredForHandle != distro) return
        scope.launch {
            val dest = destHash(context, "register") ?: return@launch
            publishAnnounce(context, dest, distro)
        }
    }

    private suspend fun publishAnnounce(context: Context, dest: ByteArray, distro: Long) {
        // [announce_name | nil, nil, [0xD0]]: the distro is anonymous unless
        // the user filled in the public Announce Display Name.
        val announceName = UserPreferences.getAnnounceDisplayName(context)
        val payload = RetichatBridge.distroAnnouncePayload(distro, announceName) ?: run {
            Log.w(TAG, "announce payload: ${RetichatBridge.lastError()}")
            return
        }
        val resp = ConnectionStateManager.appLinkSend(dest, "rfed", "distro.register", "/rfed/distro/announce", payload)
        if (DistroCodec.isAffirmative(resp)) {
            _status.value = _status.value.copy(announced = true)
            Log.i(TAG, "RFed is now announcing the distro address")
        } else {
            // No answer (offline, the link not up inside its 5 s) is said as no
            // answer, a refusal with what came back. Until 2026-10-10 both read
            // "RFed refused the pre-signed announce (resp=null bytes)".
            val said = DistroCodec.notAYes("pre-signed announce", resp)
            _status.value = _status.value.copy(announced = false, lastError = said)
            Log.w(TAG, said)
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

    /**
     * Drain fan-out copies RFed held for this device while it was unreachable,
     * up to [PULL_ROUNDS_MAX] pages. A pull whose last round still reported
     * more pending says so as a WARNING ([DistroPullRounds]); the rest waits
     * for the next pull, which is the next event (a wake, the stream's open,
     * the app coming to the foreground), never a timer. Returns the blobs it
     * handled; [pullOutcome] says how it ended.
     */
    suspend fun pull(context: Context): Int = pullOutcome(context).blobs

    /**
     * How one pull ended: the blobs it handled, why it stopped short of RFed's
     * last answer (null when every round it ran was answered: said in the log
     * already), and whether RFed still had more when the round cap stopped it.
     */
    class PullOutcome(val blobs: Int, val unfinished: String?, val morePending: Boolean = false)

    /**
     * One pull at a time, in the order they are asked for (DESIGN_PRINCIPLES
     * §5). RFed drains each page as it serves it, so two pulls at once split the
     * queue between them: the stack start's pull ([DistroFirst]) could come back
     * empty and let the persisted channels be re-subscribed while a wake's pull
     * still held a sibling's leave, the gap this closes. Each pull is bounded by
     * its rounds' own 5 s budgets, so the next one always gets its turn.
     */
    private val pullLock = Mutex()

    /** [pull], with how it ended. Never throws for a round that got no answer. */
    suspend fun pullOutcome(context: Context): PullOutcome = withContext(Dispatchers.IO) {
        if (DistroManager.identityHandle == 0L) return@withContext PullOutcome(0, "no distro held")
        val dest = destHash(context, "register") ?: run {
            Log.w(TAG, "pull: no RFed node configured")
            return@withContext PullOutcome(0, "no RFed node configured")
        }
        pullLock.withLock {
            // A Settings restart stops the stack under a round still waiting: its
            // round then gets no answer, which is that restart's doing, not a
            // failure (the truth rule; Retichat-js says the same of reconnect()).
            val stops = StackRuntime.stops
            var drained = 0
            var unfinished: String? = null
            var lastMore = false
            val total = DistroPullRounds.drain(PULL_ROUNDS_MAX, warn = { Log.w(TAG, it) }) {
                val resp = ConnectionStateManager.appLinkSend(dest, "rfed", "distro.register", "/rfed/pull", DistroCodec.MSGPACK_NIL)
                if (resp == null) {
                    unfinished = DistroPullRounds.noAnswer(stoppedSince = StackRuntime.stops != stops)
                    return@drain null
                }
                val (pairs, more) = DistroCodec.decodePullResponse(resp) ?: run {
                    unfinished = "malformed response (${resp.size} bytes)"
                    return@drain null
                }
                // RFed drained this page as it served it: a blob that throws is
                // dropped, and the rest of the page is still handled (PullPage).
                PullPage.handleEach(pairs, { _, blob -> handleBlob(context, blob) }) { id, e ->
                    Log.w(TAG, "pull: blob $id unreadable, dropped", e)
                }
                drained += pairs.size
                lastMore = more
                _status.value = _status.value.copy(lastPullCount = drained)
                DistroPullRounds.Page(pairs.size, more)
            }
            if (unfinished == null) Log.i(TAG, "pull drained $total blob(s)")
            else Log.w(TAG, "pull stopped after $total blob(s): $unfinished")
            PullOutcome(total, unfinished, morePending = unfinished == null && lastMore)
        }
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
        // DISPLAY_NAMES.md §5.2: the sender's 0xD1 and how far its signature
        // checked out; the repository decides whether to accept the name.
        val nameField = NameField.fromState(
            o.optInt("display_name_state", 0),
            if (o.isNull("display_name")) null else o.optString("display_name"),
        )
        val unverifiedReason = Signature.reason(
            o.optBoolean("signature_validated", false),
            if (o.isNull("unverified_reason")) null else o.optInt("unverified_reason", Signature.INVALID),
        )
        // The message's LXMF fields map (attachments and all), base64 msgpack
        // or null. opt, not optString: optString turns JSON null into "null".
        // Unreadable fields cost the attachments, never the message.
        val fieldsValue = o.opt("fields")
        val fieldsRaw = DistroCodec.fieldsBytes(fieldsValue)
        if (fieldsRaw == null && fieldsValue != null && fieldsValue != JSONObject.NULL) {
            Log.w(TAG, "unreadable fields from ${srcHex.take(8)} — delivering without attachments")
        }

        // RFed SPEC §17.12: a channel membership message from another device of this
        // distro. Read before the generic fan-out dedupe and before the delivery-
        // notification test below, which C also passes: it carries an empty 0x0C and
        // no content so that clients older than §17.12 drop it. It is judged on its
        // own repeat key: every C has source D and empty content, so the generic
        // key (source and timestamp) would drop the second of two siblings' Cs made
        // in the same instant, and the devices would end in different states.
        val channelSync = DistroChannelSync.read(fieldsRaw)
        if (channelSync != null) {
            handleChannelSync(context, srcHex, timestamp, channelSync, o.optBoolean("signature_validated", false))
            return
        }

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
        app.repository.onDistroMessageReceived(srcHash, title, content, timestamp, fieldsRaw, nameField, unverifiedReason)
    }

    /**
     * §17.12 "Receiving": rules 1 and 2 first (the unwrapped source is D, and its
     * LXMF signature is D's: the source hash is plaintext inside the encrypted
     * body and D's key is announced, so anyone can claim source D), then the
     * rest in [RfedChannelClient.handleDistroChannelSync]. A repeat (the live
     * fan-out and a pull, or two uploads of one C) is applied once. A dropped C
     * is not recorded as seen, so a later copy is judged again.
     */
    private suspend fun handleChannelSync(
        context: Context,
        srcHex: String,
        timestamp: Double,
        marker: DistroChannelSync.Marker,
        signatureValidated: Boolean,
    ) {
        val app = context.applicationContext as? RetichatApp ?: return
        val key = DistroChannelSync.repeatKey(srcHex, timestamp, marker)
        if (!UserPreferences.markDistroSeen(context, key)) {
            Log.i(TAG, "duplicate membership message from ${srcHex.take(8)} — skipped")
            return
        }
        val ownDistro = DistroManager.deliveryHashHex
        val fromDistro = ownDistro != null && srcHex == ownDistro
        val held = app.rfedChannelClient.handleDistroChannelSync(marker, fromDistro, fromDistro && signatureValidated)
        if (!held) UserPreferences.forgetDistroSeen(context, key)
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

/**
 * The rounds of one distro pull (`/rfed/pull`, RFed SPEC §17): each [round]
 * handles one page and says how many blobs it carried and whether RFed reported
 * more pending, or null when the round failed (it says why). At most
 * [drain]'s `maxRounds` pages per pull. With the distro sync proof (SPEC
 * §17.13) RFed lets up to 63 un-woken sync entries queue ahead of a woken
 * message (DISTRO-SYNC-PROOF-DESIGN.md §6.3, §7), so a pull that stops at its
 * round cap with more still pending is incomplete, and is said as a WARNING;
 * the rest waits for the next pull, an event, never a timer.
 */
internal object DistroPullRounds {
    class Page(val blobs: Int, val more: Boolean)

    /**
     * A round with no answer, said as it happened: the stack stopped under it (a
     * Settings restart, StackRuntime.restart, closes its link), or RFed did not
     * answer. Until 2026-10-10 both read "pull: request failed".
     */
    fun noAnswer(stoppedSince: Boolean): String =
        if (stoppedSince) "cancelled: the stack was stopped (StackRuntime.restart) while the round waited for RFed"
        else "no answer from RFed (no link inside 5 s, or the request went unanswered)"

    suspend fun drain(maxRounds: Int, warn: (String) -> Unit, round: suspend () -> Page?): Int {
        var total = 0
        for (n in 1..maxRounds) {
            val page = round() ?: return total
            total += page.blobs
            if (!page.more) return total
        }
        warn("distro pull: more pending after $maxRounds rounds ($total blobs); the rest waits for the next pull")
        return total
    }
}
