package com.retichat.app.service

import android.content.Context
import android.util.Log
import com.retichat.app.RetichatApp
import com.retichat.app.bridge.AppLinkRequestCallback
import com.retichat.app.bridge.AppLinkStatusCallback
import com.retichat.app.bridge.RetichatBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Direct port of `Retichat-ios/Retichat/Services/ConnectionStateManager.swift`.
 *
 * Event-driven connection state hierarchy:
 * 1. System network — [NetworkMonitor] tells us when connectivity changes.
 * 2. TCP transport — Rust reconnect loops handle this internally.
 * 3. Reticulum path — requested at startup and on network restore.
 * 4. APP_LINK     — established on demand via [appLinkOpen]; status fanout
 *                   via [setAppLinkStatusHandler]; never polled.
 *
 * All mutation runs under [stateMutex] on [scope] (Dispatchers.Default).
 * No timer-driven polling, no app-level retries — see DESIGN_PRINCIPLES.md
 * §1, §2, §3.
 */
object ConnectionStateManager {
    private const val TAG = "ConnState"

    /** Window in which a peer's announce justifies optimistic DIRECT. */
    private const val DIRECT_ANNOUNCE_WINDOW_MS = 120_000L

    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateMutex = Mutex()

    // Mutable state — guarded by stateMutex.
    private val peerLastSeenMs = mutableMapOf<String, Long>()
    private val activeConversationHexes = mutableSetOf<String>()
    private val appLinkStatusHandlers = mutableMapOf<String, (Int) -> Unit>()
    private var rfedLinkDestHash: ByteArray? = null
    private var registered = false

    @Volatile private var routerHandle: Long = 0L

    // Reactive view of the rfed.channel APP_LINK status, consumed by the
    // Settings status pill via collectAsState().  Updated from the
    // process-wide status callback (so it reflects every transition the
    // Rust layer reports — no polling).
    private val _rfedNodeLinkStatusFlow =
        MutableStateFlow(RetichatBridge.AppLinkStatus.NONE)
    val rfedNodeLinkStatusFlow: StateFlow<Int> =
        _rfedNodeLinkStatusFlow.asStateFlow()

    // ────────────────────────────────────────────────────────────────────
    // Setup
    // ────────────────────────────────────────────────────────────────────

    /**
     * Wire this manager to a freshly-started LXMF stack. Idempotent. Call
     * once from [StackRuntime.bootstrap] after [RetichatBridge] handles
     * are valid.
     */
    fun register(context: Context, routerHandle: Long) {
        if (routerHandle == 0L) return
        val app = (context.applicationContext as RetichatApp)
        scope.launch {
            stateMutex.withLock {
                if (registered && this@ConnectionStateManager.routerHandle == routerHandle) {
                    return@withLock
                }
                this@ConnectionStateManager.routerHandle = routerHandle

                // Aspects whose announces should also trigger app-link
                // reconnect.  LXMF only auto-reconnects under lxmf.delivery
                // out of the box — the rest must opt in.
                RetichatBridge.appLinkRegisterReconnect(routerHandle, "rfed.channel")
                RetichatBridge.appLinkRegisterReconnect(routerHandle, "rfed.notify")
                RetichatBridge.appLinkRegisterReconnect(routerHandle, "rfed.delivery")

                RetichatBridge.appLinkRegisterStatusCallback(
                    routerHandle,
                    object : AppLinkStatusCallback {
                        override fun onStatus(destHash: ByteArray, status: Int) {
                            val key = destHash.toHex()
                            // Hop onto our own scope so handlers don't run
                            // on the link-actor thread.
                            scope.launch {
                                val handler = stateMutex.withLock {
                                    // Mirror rfed.channel status into the
                                    // reactive flow consumed by the
                                    // Settings pill.
                                    val rfedKey = rfedLinkDestHash?.toHex()
                                    if (rfedKey != null && rfedKey == key) {
                                        _rfedNodeLinkStatusFlow.value = status
                                    }
                                    appLinkStatusHandlers[key]
                                }
                                handler?.invoke(status)
                            }
                        }
                    },
                )

                // Single network-change trigger per real OS event — the
                // only thing that retries an offline app-link
                // (DESIGN_PRINCIPLES.md §3, no timer polling).
                NetworkMonitor.addOnAvailableListener(networkAvailableListener)

                // Pre-open the rfed.channel app-link so the status pill
                // reflects reality on first launch.
                openRfedNodeLinkLocked(app)

                registered = true
                Log.i(TAG, "register: routerHandle=$routerHandle")
            }
        }
    }

    private val networkAvailableListener: () -> Unit = {
        val rh = routerHandle
        if (rh != 0L) {
            scope.launch(Dispatchers.IO) {
                Log.d(TAG, "network available → appLinkNetworkChanged")
                RetichatBridge.appLinkNetworkChanged(rh)
            }
        }
    }

    /** Tear down — called from [StackRuntime.shutdownNow]. */
    fun unregister() {
        scope.launch {
            stateMutex.withLock {
                NetworkMonitor.removeOnAvailableListener(networkAvailableListener)
                appLinkStatusHandlers.clear()
                rfedLinkDestHash = null
                _rfedNodeLinkStatusFlow.value = RetichatBridge.AppLinkStatus.NONE
                routerHandle = 0L
                registered = false
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // APP_LINK status handlers (per-dest fanout)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Register a handler that fires whenever the APP_LINK to [destHash]
     * changes status.  Replaces any previous handler for the same dest.
     * Pass null to remove.  Used by services that must wait for ACTIVE
     * before performing work — e.g. RfedNotifyRegistrar, channel resub.
     */
    suspend fun setAppLinkStatusHandler(destHash: ByteArray, handler: ((Int) -> Unit)?) {
        val key = destHash.toHex()
        stateMutex.withLock {
            if (handler == null) appLinkStatusHandlers.remove(key)
            else appLinkStatusHandlers[key] = handler
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Announce-driven peer reachability
    // ────────────────────────────────────────────────────────────────────

    suspend fun didReceiveAnnounce(destHash: ByteArray) {
        val key = destHash.toHex()
        stateMutex.withLock {
            peerLastSeenMs[key] = System.currentTimeMillis()
        }
    }

    /** True if an announce was seen in the last 10 minutes. */
    suspend fun isPeerRecentlySeen(destHashHex: String): Boolean = stateMutex.withLock {
        val ts = peerLastSeenMs[destHashHex] ?: return@withLock false
        System.currentTimeMillis() - ts < 600_000L
    }

    // ────────────────────────────────────────────────────────────────────
    // Delivery method selection
    // ────────────────────────────────────────────────────────────────────

    /**
     * Async equivalent of `awaitDeliveryMethod(for:)` in the iOS port.
     *
     * If an APP_LINK is already ACTIVE, returns DIRECT immediately.  If
     * it is in the middle of establishing (PATH_REQUESTED or ESTABLISHING),
     * awaits its resolution for **up to 5 s** (DESIGN_PRINCIPLES.md §1) —
     * never raise this ceiling.  Otherwise falls through to PROPAGATED
     * when no path exists.
     */
    suspend fun awaitDeliveryMethod(destHash: ByteArray): Int {
        val rh = routerHandle
        if (rh == 0L) return RetichatBridge.DeliveryMethod.PROPAGATED

        val status = RetichatBridge.appLinkStatus(rh, destHash)
        if (status == RetichatBridge.AppLinkStatus.ACTIVE) {
            return RetichatBridge.DeliveryMethod.DIRECT
        }
        if (status == RetichatBridge.AppLinkStatus.PATH_REQUESTED ||
            status == RetichatBridge.AppLinkStatus.ESTABLISHING
        ) {
            // Wait up to 5 s for ACTIVE.  Implemented as a one-shot status
            // handler + 5 s timeout — no polling.
            // // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
            val reached = awaitAppLinkActive(destHash, timeoutMs = 5_000L)
            if (reached) return RetichatBridge.DeliveryMethod.DIRECT
            return RetichatBridge.DeliveryMethod.PROPAGATED
        }

        // No link in flight — caller decides whether to OPEN one. Default
        // to PROPAGATED so we don't block message send on a fresh link
        // setup.  ChatRepository's parallel prop fallback covers the
        // race where DIRECT eventually wins.
        return RetichatBridge.DeliveryMethod.PROPAGATED
    }

    /**
     * Suspend until the APP_LINK to [destHash] reaches ACTIVE, returning
     * true on success or false on timeout.  Uses the registered status
     * callback — no polling.
     */
    private suspend fun awaitAppLinkActive(destHash: ByteArray, timeoutMs: Long): Boolean {
        val rh = routerHandle
        if (rh == 0L) return false
        if (RetichatBridge.appLinkStatus(rh, destHash) == RetichatBridge.AppLinkStatus.ACTIVE) {
            return true
        }
        val key = destHash.toHex()
        val deferred = CompletableDeferred<Boolean>()
        val handler: (Int) -> Unit = { status ->
            if (status == RetichatBridge.AppLinkStatus.ACTIVE) {
                if (!deferred.isCompleted) deferred.complete(true)
            }
        }
        // Install handler.  CAUTION: this overwrites any existing handler
        // for the same dest for the duration of the wait — restore on exit.
        val previous = stateMutex.withLock {
            val p = appLinkStatusHandlers[key]
            appLinkStatusHandlers[key] = handler
            p
        }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } == true
        } finally {
            stateMutex.withLock {
                if (previous != null) appLinkStatusHandlers[key] = previous
                else appLinkStatusHandlers.remove(key)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // APP_LINK request helper (open + wait + send, all event-driven)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Open (idempotently) an APP_LINK to [destHash] for the given app/aspects
     * tuple, wait up to 5 s for ACTIVE, then run an async request on it.
     *
     * Direct port of iOS `ConnectionStateManager.appLinkSend(...)`.  All
     * link management is delegated to the Rust APP_LINK layer — no
     * Kotlin-side one-shot links, no retries, no exponential backoff.
     * // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
     *
     * Returns the response bytes, or `null` if the link did not reach
     * ACTIVE inside 5 s or the request itself failed/timed out.
     */
    suspend fun appLinkSend(
        destHash: ByteArray,
        app: String,
        aspectsCsv: String,
        path: String,
        payload: ByteArray,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val rh = routerHandle
        if (rh == 0L) return@withContext null

        if (RetichatBridge.appLinkStatus(rh, destHash) != RetichatBridge.AppLinkStatus.ACTIVE) {
            RetichatBridge.appLinkOpen(rh, destHash, app, aspectsCsv)
        }
        // Wait up to 5 s for ACTIVE — via status callback, not polling.
        // // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
        if (!awaitAppLinkActive(destHash, timeoutMs = 5_000L)) return@withContext null

        // Async request — suspends until response/failed/timeout via the
        // single-fire callback installed in the JNI layer.
        // // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
        suspendCoroutine { cont ->
            val ok = RetichatBridge.appLinkRequestAsync(
                rh, destHash, path, payload, /* timeoutSecs = */ 5.0,
                object : AppLinkRequestCallback {
                    override fun onResult(status: Int, bytes: ByteArray?) {
                        when (status) {
                            RetichatBridge.AppLinkRequestStatus.RESPONSE -> cont.resume(bytes)
                            else -> cont.resume(null)
                        }
                    }
                },
            )
            if (!ok) cont.resume(null)
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // Conversation lifecycle (eager open while peer's screen is visible)
    // ────────────────────────────────────────────────────────────────────

    fun openConversation(peerHash: ByteArray) {
        val rh = routerHandle
        if (rh == 0L) return
        scope.launch {
            stateMutex.withLock { activeConversationHexes.add(peerHash.toHex()) }
            withContext(Dispatchers.IO) {
                RetichatBridge.appLinkOpen(rh, peerHash, "lxmf", "delivery")
            }
        }
    }

    fun closeConversation(peerHash: ByteArray) {
        val rh = routerHandle
        if (rh == 0L) return
        scope.launch {
            stateMutex.withLock { activeConversationHexes.remove(peerHash.toHex()) }
            withContext(Dispatchers.IO) {
                RetichatBridge.appLinkClose(rh, peerHash)
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // App lifecycle hooks
    // ────────────────────────────────────────────────────────────────────

    /** Call when the app returns to foreground. */
    fun onAppForeground() {
        val rh = routerHandle
        if (rh == 0L) return
        scope.launch {
            val peers = stateMutex.withLock { activeConversationHexes.mapNotNull { it.fromHexOrNull() } }
            withContext(Dispatchers.IO) {
                for (peer in peers) {
                    RetichatBridge.appLinkOpen(rh, peer, "lxmf", "delivery")
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // RFed node link (rfed.channel — kept warm while app foreground)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Open (or re-open) an APP_LINK to the configured rfed.channel
     * destination.  Held open until [closeRfedNodeLink].
     */
    fun openRfedNodeLink() {
        val app = RetichatApp.appInstance ?: return
        scope.launch {
            stateMutex.withLock { openRfedNodeLinkLocked(app) }
        }
    }

    private fun openRfedNodeLinkLocked(app: RetichatApp) {
        val rh = routerHandle
        if (rh == 0L) return
        val dest = rfedChannelDestHash(app) ?: return
        rfedLinkDestHash = dest
        // Seed the reactive flow with the current status so the pill
        // reflects reality before the first callback arrives (e.g. on
        // re-foreground when the link is already ACTIVE).
        _rfedNodeLinkStatusFlow.value = RetichatBridge.appLinkStatus(rh, dest)
        scope.launch(Dispatchers.IO) {
            RetichatBridge.appLinkOpen(rh, dest, "rfed", "channel")
        }
    }

    fun closeRfedNodeLink() {
        val rh = routerHandle
        val dest = rfedLinkDestHash ?: return
        rfedLinkDestHash = null
        _rfedNodeLinkStatusFlow.value = RetichatBridge.AppLinkStatus.NONE
        scope.launch(Dispatchers.IO) {
            if (rh != 0L) RetichatBridge.appLinkClose(rh, dest)
        }
    }

    /** Current APP_LINK status for the rfed node, or NONE if unconfigured. */
    fun rfedNodeLinkStatus(): Int {
        val rh = routerHandle
        if (rh == 0L) return RetichatBridge.AppLinkStatus.NONE
        val dest = rfedLinkDestHash
            ?: rfedChannelDestHash(RetichatApp.appInstance ?: return RetichatBridge.AppLinkStatus.NONE)
            ?: return RetichatBridge.AppLinkStatus.NONE
        return RetichatBridge.appLinkStatus(rh, dest)
    }

    /** rfed.channel destination derived from current preferences, or null. */
    fun rfedChannelDestHash(context: Context): ByteArray? {
        val identityHex = UserPreferences.getRfedNodeIdentityHash(context)
        if (identityHex.isEmpty()) return null
        val destHex = RfedChannelClient.rfedDestHash(identityHex, "rfed", listOf("channel"))
            ?: return null
        return destHex.fromHexOrNull()
    }

    // ────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ────────────────────────────────────────────────────────────────────

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.fromHexOrNull(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching {
            ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }
}
