package com.newendian.retichat.service

import android.content.Context
import android.util.Log
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.PropagationLinkCallback
import com.newendian.retichat.bridge.PropagationUploadCallback
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.bridge.LxmfFields
import com.newendian.retichat.service.DistroCodec.toHex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What this device owes its distro, on Android: the [DistroOutbox] wired to Room
 * ([RoomOutboxStore]), to the propagation link and uploads in the JNI crate
 * ([RetichatBridge.propagationUpload] and its link events), to the stack's start
 * and stop, and to the distro the device holds ([DistroManager]). RFed SPEC
 * §17.12 "On the sending device", §17.11, "The phones' outbox".
 *
 * Everything the outbox decides runs on one thread (`distro-outbox`), so each
 * event, a proof, a loss or a link coming up, runs to completion before the
 * next, as the web's single thread gives it. The JNI calls its events from Rust
 * threads, which only hand them over. Building an upload (its stamp) runs on
 * the IO dispatcher.
 *
 * It never starts the propagation link: the link is the one the router holds
 * for the propagation node ([PropagationSync] and the send paths set the node
 * and open it), and what is owed waits for that link to come up.
 *
 * What the stack does not tell us: a STALE propagation link that recovers
 * because the node is heard on it again. Reticulum-rust's `Link::receive` makes
 * it ACTIVE again with no callback, and AppLinks reports no status for it, so
 * [DistroOutbox.onLinkRecovered] is not called from here. (A held STALE link is
 * replaced by a new one when the node announces: that is an establishment, and
 * it is observed.) See the outstanding item in the report on this work.
 */
object DistroOutboxRuntime {
    private const val TAG = "DistroOutbox"

    private val serial = Executors.newSingleThreadExecutor { r -> Thread(r, "distro-outbox").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + serial)

    private val _lastError = MutableStateFlow<String?>(null)

    /** The last thing the outbox said as an error (an upload not proved, a write refused), for a screen that shows it. */
    val lastError: StateFlow<String?> = _lastError

    private val boxLock = Mutex()
    @Volatile private var box: DistroOutbox? = null
    @Volatile private var port: JniPropagationPort? = null
    @Volatile private var linkEventsRegistered = false
    private var distroWatch = false

    private object AndroidLog : OutboxLog {
        override fun info(message: String) { Log.i(TAG, message) }
        override fun warn(message: String) { Log.w(TAG, message) }
        override fun error(message: String) { Log.e(TAG, message); _lastError.value = message }
    }

    private fun heldDistro(): HeldDistro? =
        DistroManager.state.value?.let { HeldDistro(it.deliveryHashHex, it.publicKeyHex) }

    /** The outbox, made and loaded from storage on first use. */
    private suspend fun outbox(context: Context): DistroOutbox = boxLock.withLock {
        box?.let { return@withLock it }
        val app = context.applicationContext as RetichatApp
        val jni = JniPropagationPort(app, scope)
        val created = DistroOutbox(RoomOutboxStore(app.database.distroOutboxDao()), jni, ::heldDistro, AndroidLog, scope)
        withContext(serial) { created.start() }
        port = jni
        box = created
        if (!distroWatch) {
            distroWatch = true
            // Every change of the distro held (forgotten, generated, imported), and once at load:
            // the membership messages owed to a distro no longer held are dropped (James, 2026-10-03).
            scope.launch { DistroManager.state.collect { created.onDistroChanged() } }
        }
        created
    }

    /**
     * The stack is up (called from its bootstrap, once the router exists): load
     * what a previous run left owed and listen for the propagation link. The
     * link's coming-up (a new link's establishment) is what uploads it. A
     * propagation link that is already ACTIVE when this runs came up before it
     * listened, and is that link's coming-up all the same.
     */
    suspend fun start(context: Context) {
        val outbox = outbox(context)
        withContext(serial) { outbox.start() }
        if (!linkEventsRegistered) {
            if (RetichatBridge.propagationLinkEventsRegister(linkCallback)) linkEventsRegistered = true
            else Log.e(TAG, "link events not registered: ${RetichatBridge.lastError()}")
        }
        val held = port?.held()
        if (held != null && held.active) scope.launch { outbox.onLinkEstablished(held.id) }
    }

    /**
     * The stack is stopping: no proof can come for an upload still open, so
     * each is decided not proved, and nothing is sent. The stack's shutdown
     * drops the link callbacks; the next [start] registers them again.
     */
    fun stop() {
        linkEventsRegistered = false
        val outbox = box ?: return
        scope.launch { outbox.onStop() }
    }

    private val linkCallback = object : PropagationLinkCallback {
        override fun onLink(destHash: ByteArray, status: Int, linkId: ByteArray?) {
            val node = port?.node() ?: return
            if (!destHash.contentEquals(node)) return
            val id = linkId?.takeIf { it.isNotEmpty() }?.toHex()
            Log.d(TAG, "propagation link status=$status link=${id?.take(8)}")
            // A new propagation link's establishment is its ACTIVE event, carrying the link.
            if (status == RetichatBridge.AppLinkStatus.ACTIVE && id != null) {
                val outbox = box ?: return
                scope.launch { outbox.onLinkEstablished(id) }
            }
        }
    }

    // ---- What the user's actions owe ----

    /**
     * The user joined or left [channelName] on this device ([op] "join" or
     * "leave", at [atMs] from [DistroChannelSync.stampLocal]): with a distro,
     * owe it the membership message C (RFed SPEC §17.12 "Who sends it"). The
     * message is packed and signed by the distro once, here, and kept until the
     * node proves it. A later action on the channel replaces it. Nothing here
     * waits for the network: the upload is made on its own coroutine. Never throws.
     */
    suspend fun oweChannelSync(context: Context, op: String, channelName: String, channelHashHex: String, atMs: Long) {
        try {
            val (distro, signer) = DistroManager.signing() ?: return
            val deviceHex = StackRuntime.selfDestHash.toHex()
            if (!DistroCodec.isHex32(deviceHex)) {
                Log.w(TAG, "the $op of #$channelName not sent to the distro: this device's address is not known (§17.12)")
                return
            }
            val label = "the $op of #$channelName (§17.12)"
            val (packed, hash) = pack(distro, signer) { handle ->
                RetichatBridge.messageSetDistroChannelSync(handle, op, channelName, atMs, deviceHex)
            } ?: return
            val entry = OwedEntry(
                id = DistroChannelSync.entryId(channelHashHex), kind = OwedKind.CHANNEL,
                distro = distro.deliveryHashHex, distroKey = distro.publicKeyHex,
                msgHash = hash, packed = packed, label = label, op = op, name = channelName, atMs = atMs,
            )
            val outbox = outbox(context)
            withContext(serial) { outbox.owe(entry) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The $op of #$channelName was not sent to the distro (§17.12): ${e.message}")
            _lastError.value = "The $op of #$channelName was not sent to the distro: ${e.message}"
        }
    }

    /**
     * The §17.11 sent-copy of a message this device just sent as the distro to
     * [recipient]: the same text, from D to D, with the recipient (0xFC) and
     * this device's address (0xFD), packed and signed once and owed until the
     * node proves it. Never throws.
     */
    suspend fun oweSentCopy(context: Context, recipient: ByteArray, title: String, content: String) {
        try {
            val (distro, signer) = DistroManager.signing() ?: return
            val recipientHex = recipient.toHex()
            val deviceHex = StackRuntime.selfDestHash.toHex()
            if (!DistroCodec.isHex32(deviceHex)) {
                Log.w(TAG, "sent-copy: this device's address is not known — no copy for ${recipientHex.take(8)}")
                return
            }
            val (packed, hash) = pack(distro, signer, title = title, content = content) { handle ->
                RetichatBridge.messageAddFieldString(handle, LxmfFields.FIELD_CUSTOM_TYPE, LxmfFields.DISTRO_SENT_TYPE) &&
                    RetichatBridge.messageAddFieldString(handle, LxmfFields.FIELD_CUSTOM_DATA, recipientHex) &&
                    RetichatBridge.messageAddFieldString(handle, LxmfFields.FIELD_CUSTOM_META, deviceHex)
            } ?: return
            val entry = OwedEntry(
                id = "sent:$hash", kind = OwedKind.SENT,
                distro = distro.deliveryHashHex, distroKey = distro.publicKeyHex,
                msgHash = hash, packed = packed, label = "the sent-copy for ${recipientHex.take(8)} (§17.11)", toHex = recipientHex,
            )
            val outbox = outbox(context)
            withContext(serial) { outbox.owe(entry) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The sent-copy for ${recipient.toHex().take(8)} was not sent to the distro (§17.11): ${e.message}")
            _lastError.value = "A sent message was not copied to the distro: ${e.message}"
        }
    }

    /**
     * Make a message from the distro to the distro (its key, the identity
     * [signer], signs it), set its fields with [fields], pack and sign it once, and return its
     * LXMF bytes and message hash (hex). Null, said, when any step fails. The
     * message is made DIRECT only so that packing needs no destination: it is
     * never sent from here; only the bytes it packs to are kept.
     */
    private suspend fun pack(
        distro: DistroManager.DistroState,
        signer: Long,
        title: String = "",
        content: String = "",
        fields: (Long) -> Boolean,
    ): Pair<ByteArray, String>? = withContext(Dispatchers.IO) {
        val distroHash = DistroCodec.hexToBytes(distro.deliveryHashHex) ?: return@withContext null
        val handle = RetichatBridge.messageCreate(
            destHash = distroHash, srcHash = distroHash, content = content, title = title,
            method = RetichatBridge.DeliveryMethod.DIRECT, identityHandle = signer,
        )
        if (handle == 0L) {
            Log.e(TAG, "messageCreate failed: ${RetichatBridge.lastError()}")
            return@withContext null
        }
        try {
            if (!fields(handle)) {
                Log.e(TAG, "setting the fields failed: ${RetichatBridge.lastError()}")
                return@withContext null
            }
            val packed = RetichatBridge.messagePack(handle)
            val hash = RetichatBridge.messageGetHash(handle)?.toHex()
            if (packed == null || hash == null) {
                Log.e(TAG, "packing failed: ${RetichatBridge.lastError()}")
                return@withContext null
            }
            packed to hash
        } finally {
            RetichatBridge.messageDestroy(handle)
        }
    }

    // ---- The JNI side of the outbox's port ----

    /** [PropagationPort] over the JNI crate: the link AppLinks holds for the propagation node, and uploads on it. */
    private class JniPropagationPort(private val context: Context, private val scope: CoroutineScope) : PropagationPort {
        /**
         * The propagation node: the one every send path and the sync set on the
         * router ([PropagationSync.resolvePropagationOverride]: the Settings
         * override, else the RFed node's own lxmf.propagation). Null when none is known.
         */
        fun node(): ByteArray? {
            if (!RetichatBridge.isLoaded) return null
            val hex = PropagationSync.resolvePropagationOverride(context) ?: return null
            return DistroCodec.hexToBytes(hex.trim().lowercase())?.takeIf { it.size == 16 }
        }

        override fun held(): HeldLink? {
            val node = node() ?: return null
            val link = RetichatBridge.propagationLink(node) ?: return null
            return HeldLink(link.id, link.active)
        }

        override suspend fun build(entry: OwedEntry): ByteArray = withContext(Dispatchers.IO) {
            val node = node() ?: throw IllegalStateException("no propagation node is configured")
            val key = DistroCodec.hexToBytes(entry.distroKey) ?: throw IllegalStateException("the distro's key is not hex")
            RetichatBridge.propagationBuildUpload(key, entry.packed, node)
                ?: throw IllegalStateException(RetichatBridge.lastError() ?: "the upload could not be built")
        }

        override fun upload(upload: ByteArray, sink: UploadSink): UploadLeft? {
            val node = node() ?: return null
            val decided = AtomicBoolean(false)
            val callback = object : PropagationUploadCallback {
                override fun onProved() { decided.set(true); scope.launch { sink.proved() } }
                override fun onLost() { decided.set(true); scope.launch { sink.lost() } }
            }
            val started = RetichatBridge.propagationUpload(node, upload, callback)
            if (started == null) {
                Log.w(TAG, "upload did not leave: ${RetichatBridge.lastError()}")
                return null
            }
            if (!started.resource) {
                // DESIGN_PRINCIPLES §1: the node proves a packet it holds at once. At 5 s with no proof
                // the violation is logged; it decides nothing (the proof, or the packet's loss, does).
                scope.launch {
                    delay(DistroOutbox.PROOF_LIVE_MS + 1)
                    if (!decided.get()) {
                        // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
                        Log.e(TAG, "§1 VIOLATION: the propagation node has not proved an upload within " +
                            "${DistroOutbox.PROOF_LIVE_MS / 1000} s; its proof, or the packet's loss, decides it")
                    }
                }
            }
            return UploadLeft(started.linkId, started.resource)
        }
    }
}
