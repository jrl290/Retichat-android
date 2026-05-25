package com.newendian.retichat.service

import android.content.Context
import android.util.Log
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.AppLinkPacketCallback
import com.newendian.retichat.bridge.AppLinkRequestCallback
import com.newendian.retichat.bridge.AppLinkSendCallback
import com.newendian.retichat.bridge.AppLinkStatusCallback
import com.newendian.retichat.bridge.RetichatBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    internal fun prefersPersistentAppLink(app: String, aspectsCsv: String): Boolean {
        val normalizedApp = app.trim()
        val segments = aspectsCsv
            .split('.', ',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .let { parsed ->
                if (parsed.firstOrNull() == normalizedApp) parsed.drop(1) else parsed
            }

        return normalizedApp == "rfed" && (
            segments == listOf("channel", "stream") ||
                segments == listOf("propagation", "stream")
            )
    }

    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateMutex = Mutex()

    // Mutable state — guarded by stateMutex.
    private val peerLastSeenMs = mutableMapOf<String, Long>()
    private val activeConversationHexes = mutableSetOf<String>()
    private val appLinkStatusHandlers = mutableMapOf<String, (Int) -> Unit>()
    private val appLinkActiveWaiters = mutableMapOf<String, MutableList<CompletableDeferred<Boolean>>>()
    private val essentialReadyHandlers = mutableMapOf<String, () -> Unit>()
    private var registered = false

    private val essentialRequestMutex = Mutex()
    private val rfedMonitorLock = Any()
    private var rfedStatusMonitorRetainCount = 0
    private var rfedStatusMonitorJob: Job? = null

    @Volatile private var routerHandle: Long = 0L

    // Reactive view of rfed.node reachability, consumed by the Settings
    // status pill. We reuse the app-link status constants for color mapping:
    // NONE=no config, ACTIVE=path present, DISCONNECTED=no path.
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
    suspend fun register(context: Context, routerHandle: Long) {
        if (routerHandle == 0L) return
        val app = (context.applicationContext as RetichatApp)
        stateMutex.withLock {
            if (registered && this@ConnectionStateManager.routerHandle == routerHandle) {
                return
            }
            this@ConnectionStateManager.routerHandle = routerHandle

            // Aspects whose announces should also trigger app-link
            // reconnect.  LXMF only auto-reconnects under lxmf.delivery
            // out of the box — the rest must opt in.  Legacy aspects
            // (rfed.channel, rfed.notify) remain registered because the
            // server still announces them for back-compat; the split
            // aspects below are the canonical ones the app uses.
            RetichatBridge.appLinkRegisterReconnect(routerHandle, "rfed.channel")
            RetichatBridge.appLinkRegisterReconnect(routerHandle, "rfed.notify")
            RetichatBridge.appLinkRegisterReconnect(routerHandle, "rfed.delivery")
            RetichatBridge.appLinkRegisterReconnect(routerHandle, "rfed.channel.subscribe")
            RetichatBridge.appLinkRegisterReconnect(routerHandle, "rfed.channel.unsubscribe")
            RetichatBridge.appLinkRegisterReconnect(routerHandle, "rfed.channel.publish")
            RetichatBridge.appLinkRegisterReconnect(routerHandle, "rfed.channel.pull")
            RetichatBridge.appLinkRegisterReconnect(routerHandle, "rfed.channel.stream")
            RetichatBridge.appLinkRegisterReconnect(routerHandle, "rfed.propagation.stream")
            RetichatBridge.appLinkRegisterReconnect(routerHandle, "rfed.notify.register")
            RetichatBridge.appLinkRegisterReconnect(routerHandle, "rfed.notify.unregister")
            RetichatBridge.appLinkRegisterReconnect(routerHandle, "fcm.register")
            RetichatBridge.appLinkRegisterReconnect(routerHandle, "fcm.unregister")
            RetichatBridge.appLinkRegisterReconnect(routerHandle, "apns.relay")
            RetichatBridge.appLinkRegisterReconnect(routerHandle, "apns.register")
            RetichatBridge.appLinkRegisterReconnect(routerHandle, "apns.unregister")

            RetichatBridge.appLinkRegisterStatusCallback(
                routerHandle,
                object : AppLinkStatusCallback {
                    override fun onStatus(destHash: ByteArray, status: Int) {
                        val key = destHash.toHex()
                        // Hop onto our own scope so handlers don't run
                        // on the link-actor thread.
                        scope.launch {
                                val (handler, waiters) = stateMutex.withLock {
                                    val handler = appLinkStatusHandlers[key]
                                    val waiters = if (status == RetichatBridge.AppLinkStatus.ACTIVE) {
                                        appLinkActiveWaiters.remove(key).orEmpty().toList()
                                    } else {
                                        emptyList()
                                    }
                                    handler to waiters
                                }
                            handler?.invoke(status)
                                waiters.forEach {
                                    if (!it.isCompleted) {
                                        it.complete(true)
                                    }
                                }
                        }
                    }
                },
            )

            // Single network-change trigger per real OS event — the
            // only thing that retries an offline app-link
            // (DESIGN_PRINCIPLES.md §3, no timer polling).
            NetworkMonitor.addOnAvailableListener(networkAvailableListener)

            requestEssentialPaths(app)
            openRfedNodeLinkLocked(app)

            registered = true
            Log.i(TAG, "register: routerHandle=$routerHandle")
        }
    }

    private val networkAvailableListener: () -> Unit = {
        val rh = routerHandle
        if (rh != 0L) {
            scope.launch(Dispatchers.IO) {
                Log.d(TAG, "network available → appLinkNetworkChanged")
                RetichatBridge.appLinkNetworkChanged(rh)
                onNetworkReconnect()
            }
        }
    }

    /** Tear down — called from [StackRuntime.shutdownNow]. */
    fun unregister() {
        scope.launch {
            stateMutex.withLock {
                NetworkMonitor.removeOnAvailableListener(networkAvailableListener)
                appLinkStatusHandlers.clear()
                appLinkActiveWaiters.clear()
                essentialReadyHandlers.clear()
                _rfedNodeLinkStatusFlow.value = RetichatBridge.AppLinkStatus.NONE
                routerHandle = 0L
                registered = false
            }
            synchronized(rfedMonitorLock) {
                rfedStatusMonitorRetainCount = 0
                rfedStatusMonitorJob?.cancel()
                rfedStatusMonitorJob = null
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

    fun registerAppLinkPacketCallback(
        destHash: ByteArray,
        callback: AppLinkPacketCallback,
    ): Boolean {
        val rh = routerHandle
        if (rh == 0L) return false
        return RetichatBridge.appLinkRegisterPacketCallback(rh, destHash, callback)
    }

    /** Register a handler that fires when a required infrastructure destination is ready. */
    suspend fun setEssentialDestinationReadyHandler(destHash: ByteArray, handler: (() -> Unit)?) {
        val key = destHash.toHex()
        stateMutex.withLock {
            if (handler == null) essentialReadyHandlers.remove(key)
            else essentialReadyHandlers[key] = handler
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

    fun appLinkStatus(destHash: ByteArray): Int {
        val rh = routerHandle
        if (rh == 0L) return RetichatBridge.AppLinkStatus.NONE
        return RetichatBridge.appLinkStatus(rh, destHash)
    }

    // ────────────────────────────────────────────────────────────────────
    // APP_LINK request helper (open + wait + send, all event-driven)
    // ────────────────────────────────────────────────────────────────────

    /**
     * Suspend until the APP_LINK to [destHash] reaches ACTIVE, returning
     * true on success or false on timeout. Used by [appLinkSend].
     * No polling — uses the registered status callback.
     */
    private suspend fun awaitAppLinkActive(destHash: ByteArray, timeoutMs: Long): Boolean {
        val rh = routerHandle
        if (rh == 0L) return false
        if (RetichatBridge.appLinkStatus(rh, destHash) == RetichatBridge.AppLinkStatus.ACTIVE) {
            return true
        }
        val key = destHash.toHex()
        val deferred = CompletableDeferred<Boolean>()
        stateMutex.withLock {
            appLinkActiveWaiters.getOrPut(key) { mutableListOf() }.add(deferred)
        }
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } == true
        } finally {
            stateMutex.withLock {
                appLinkActiveWaiters[key]?.removeAll { it === deferred || it.isCompleted }
                if (appLinkActiveWaiters[key].isNullOrEmpty()) {
                    appLinkActiveWaiters.remove(key)
                }
            }
        }
    }

    /**
     * Open (idempotently) an APP_LINK to [destHash] for the given app/aspects
     * tuple, wait up to 5 s for ACTIVE, then issue the request.
     *
     * Persistent stream destinations keep and reuse an APP_LINK handle.
     * One-shot request destinations use APP_LINK only as the readiness gate,
     * then perform an authenticated transient link request so wake pulls do
     * not retain ownership of the outbound link.
     * // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
     *
     * Returns the response bytes, or `null` if readiness or the request
     * itself failed inside the 5 s budget.
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

        val usePersistentLink = prefersPersistentAppLink(app, aspectsCsv)

        if (RetichatBridge.appLinkStatus(rh, destHash) != RetichatBridge.AppLinkStatus.ACTIVE) {
            if (usePersistentLink) {
                RetichatBridge.appLinkOpenPersistent(rh, destHash, app, aspectsCsv)
            } else {
                RetichatBridge.appLinkOpen(rh, destHash, app, aspectsCsv)
            }
        }
        // Wait up to 5 s for ACTIVE — via status callback, not polling.
        // // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
        if (!awaitAppLinkActive(destHash, timeoutMs = 5_000L)) return@withContext null

        if (!usePersistentLink) {
            val identityHandle = StackRuntime.identityHandle
            if (identityHandle == 0L) return@withContext null
            @Suppress("DEPRECATION")
            val response = RetichatBridge.linkRequest(
                destHash,
                app,
                aspectsCsv,
                identityHandle,
                path,
                payload,
                5.0,
            )
            return@withContext response
        }

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

    /** Prime an APP_LINK so services can wait for ACTIVE without owning a request handle. */
    fun primeAppLink(destHash: ByteArray, app: String, aspectsCsv: String) {
        val rh = routerHandle
        if (rh == 0L) return
        scope.launch(Dispatchers.IO) {
            if (prefersPersistentAppLink(app, aspectsCsv)) {
                RetichatBridge.appLinkOpenPersistent(rh, destHash, app, aspectsCsv)
            } else {
                RetichatBridge.appLinkOpen(rh, destHash, app, aspectsCsv)
            }
        }
    }

    /** Send a plain DATA packet via AppLinks and await delivery proof or terminal failure. */
    suspend fun appLinkSendData(
        destHash: ByteArray,
        app: String,
        aspectsCsv: String,
        payload: ByteArray,
    ): Boolean = withContext(Dispatchers.IO) {
        val rh = routerHandle
        if (rh == 0L) return@withContext false

        suspendCoroutine { cont ->
            val ok = RetichatBridge.appLinkSendAsync(
                rh,
                destHash,
                app,
                aspectsCsv,
                payload,
                object : AppLinkSendCallback {
                    override fun onResult(status: Int) {
                        cont.resume(status == RetichatBridge.AppLinkSendStatus.DELIVERED)
                    }
                },
            )
            if (!ok) cont.resume(false)
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
            requestEssentialPaths()
            val peers = stateMutex.withLock { activeConversationHexes.mapNotNull { it.fromHexOrNull() } }
            withContext(Dispatchers.IO) {
                for (peer in peers) {
                    RetichatBridge.appLinkOpen(rh, peer, "lxmf", "delivery")
                }
            }
        }
    }

    /** Re-request infrastructure paths and peer routes after real network recovery. */
    fun onNetworkReconnect() {
        val rh = routerHandle
        if (rh == 0L) return
        scope.launch {
            requestEssentialPaths()
            openRfedNodeLink()
            val peers = stateMutex.withLock { activeConversationHexes.mapNotNull { it.fromHexOrNull() } }
            withContext(Dispatchers.IO) {
                for (peer in peers) {
                    RetichatBridge.transportRequestPath(peer)
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────
    // RFed node reachability (path-only, no held app-link)
    // ───────────────────────────────────────────────────────────────────────

    /**
     * Request a path to the configured rfed.node destination so foreground
     * UI can reflect reachability and channel startup can wait for the route.
     */
    fun openRfedNodeLink() {
        val app = RetichatApp.appInstance ?: return
        scope.launch {
            stateMutex.withLock { openRfedNodeLinkLocked(app) }
        }
    }

    private fun openRfedNodeLinkLocked(app: RetichatApp) {
        val dest = rfedNodeDestHash(app, includeHiddenDefault = true) ?: return
        refreshRfedNodeStatus()
        scope.launch(Dispatchers.IO) {
            RetichatBridge.transportRequestPath(dest)
            refreshRfedNodeStatus()
        }
    }

    fun closeRfedNodeLink() {
        _rfedNodeLinkStatusFlow.value = RetichatBridge.AppLinkStatus.NONE
    }

    /** Current path reachability status for the rfed node, or NONE if unconfigured. */
    fun rfedNodeLinkStatus(): Int {
        val rh = routerHandle
        if (rh == 0L) return RetichatBridge.AppLinkStatus.NONE
        val dest = rfedNodeDestHash(
            RetichatApp.appInstance ?: return RetichatBridge.AppLinkStatus.NONE,
            includeHiddenDefault = false,
        )
            ?: return RetichatBridge.AppLinkStatus.NONE
        return if (RetichatBridge.transportHasPath(dest)) {
            RetichatBridge.AppLinkStatus.ACTIVE
        } else {
            RetichatBridge.AppLinkStatus.DISCONNECTED
        }
    }

    /** Wait for rfed.node reachability, mirroring iOS `waitForRfedReachable`. */
    suspend fun waitForRfedReachable(timeoutMs: Long): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (rfedNodeLinkStatusRuntime() == RetichatBridge.AppLinkStatus.ACTIVE) {
                return@withContext true
            }
            delay(250)
        }
        false
    }

    /** Poll rfed.node reachability while the Settings screen is visible. */
    fun retainRfedNodeStatusMonitor() {
        synchronized(rfedMonitorLock) {
            rfedStatusMonitorRetainCount += 1
            if (rfedStatusMonitorRetainCount != 1) return
            refreshRfedNodeStatus()
            rfedStatusMonitorJob?.cancel()
            rfedStatusMonitorJob = scope.launch(Dispatchers.IO) {
                while (true) {
                    delay(3_000)
                    refreshRfedNodeStatus()
                }
            }
        }
    }

    fun releaseRfedNodeStatusMonitor() {
        synchronized(rfedMonitorLock) {
            if (rfedStatusMonitorRetainCount == 0) return
            rfedStatusMonitorRetainCount -= 1
            if (rfedStatusMonitorRetainCount == 0) {
                rfedStatusMonitorJob?.cancel()
                rfedStatusMonitorJob = null
            }
        }
    }

    /** rfed.node destination derived from current preferences, or null. */
    fun rfedNodeDestHash(context: Context, includeHiddenDefault: Boolean = false): ByteArray? {
        val identityHex = if (includeHiddenDefault) {
            UserPreferences.getEffectiveRfedNodeIdentityHash(context)
        } else {
            UserPreferences.getRfedNodeIdentityHash(context)
        }
        if (identityHex.isEmpty()) return null
        val destHex = RfedChannelClient.rfedDestHash(identityHex, "rfed", listOf("node"))
            ?: return null
        return destHex.fromHexOrNull()
    }

    private fun rfedNodeLinkStatusRuntime(): Int {
        val rh = routerHandle
        if (rh == 0L) return RetichatBridge.AppLinkStatus.NONE
        val dest = rfedNodeDestHash(
            RetichatApp.appInstance ?: return RetichatBridge.AppLinkStatus.NONE,
            includeHiddenDefault = true,
        ) ?: return RetichatBridge.AppLinkStatus.NONE
        return if (RetichatBridge.transportHasPath(dest)) {
            RetichatBridge.AppLinkStatus.ACTIVE
        } else {
            RetichatBridge.AppLinkStatus.DISCONNECTED
        }
    }

    private fun refreshRfedNodeStatus() {
        _rfedNodeLinkStatusFlow.value = rfedNodeLinkStatus()
    }

    private fun requestEssentialPaths(context: Context? = RetichatApp.appInstance) {
        val appContext = context?.applicationContext ?: return

        data class PendingEssential(
            val hash: ByteArray,
            val label: String,
            val needsPath: Boolean,
            val needsIdentity: Boolean,
        )

        val destinations = linkedMapOf<String, Pair<String, ByteArray>>()
        fun appendDestination(name: String, hash: ByteArray) {
            destinations.putIfAbsent(hash.toHex(), name to hash)
        }

        val rfedIdentityHex = UserPreferences.getEffectiveRfedNodeIdentityHash(appContext)
        val effectivePropHex = PropagationSync.resolvePropagationOverride(appContext).orEmpty()
        val derivedPropHex = if (rfedIdentityHex.length == 32) {
            FcmTokenRegistrar.rnsDestHash(rfedIdentityHex, "lxmf", listOf("propagation")).orEmpty()
        } else {
            ""
        }

        val rfedCloneSources = mutableListOf<Pair<String, ByteArray>>()
        val rfedServiceTargets = mutableListOf<Pair<String, ByteArray>>()

        FcmBridgeHashes.registrationHex(appContext)
            ?.fromHexOrNull()
            ?.let { appendDestination("fcm.register", it) }
        FcmBridgeHashes.relayHex(appContext)
            ?.fromHexOrNull()
            ?.let { appendDestination("fcm.relay", it) }

        if (rfedIdentityHex.length == 32) {
            RfedChannelClient.rfedDestHash(rfedIdentityHex, "rfed", listOf("node"))
                ?.fromHexOrNull()
                ?.let {
                    appendDestination("rfed.node", it)
                    rfedCloneSources += "rfed.node" to it
                }

            RfedChannelClient.rfedDestHash(rfedIdentityHex, "rfed", listOf("channel"))
                ?.fromHexOrNull()
                ?.let {
                    appendDestination("rfed.channel", it)
                    rfedServiceTargets += "rfed.channel" to it
                }

            if (derivedPropHex.isNotEmpty() && effectivePropHex == derivedPropHex) {
                derivedPropHex.fromHexOrNull()?.let {
                    rfedCloneSources += "lxmf.propagation" to it
                }
            }

            val splitAspects = listOf(
                "rfed.notify.register" to listOf("notify", "register"),
                "rfed.notify.unregister" to listOf("notify", "unregister"),
                "rfed.channel.subscribe" to listOf("channel", "subscribe"),
                "rfed.channel.unsubscribe" to listOf("channel", "unsubscribe"),
                "rfed.channel.publish" to listOf("channel", "publish"),
                "rfed.channel.pull" to listOf("channel", "pull"),
                "rfed.propagation.stream" to listOf("propagation", "stream"),
            )
            for ((label, aspects) in splitAspects) {
                val hash = RfedChannelClient.rfedDestHash(rfedIdentityHex, "rfed", aspects)
                    ?.fromHexOrNull()
                    ?: continue
                appendDestination(label, hash)
                rfedServiceTargets += label to hash
            }

            RfedChannelClient.rfedDestHash(rfedIdentityHex, "rfed", listOf("delivery"))
                ?.fromHexOrNull()
                ?.let {
                    appendDestination("rfed.delivery", it)
                    rfedServiceTargets += "rfed.delivery" to it
                }
        }

        val nodeCandidates = PropagationNodeManager(
            userConfiguredHash = effectivePropHex.ifEmpty { null },
        ).orderedNodeHashes().toMutableList()
        if (effectivePropHex.isNotEmpty()) {
            val configured = effectivePropHex.fromHexOrNull()
            if (configured != null && nodeCandidates.none { it.contentEquals(configured) }) {
                nodeCandidates += configured
            }
        }
        for (nodeHash in nodeCandidates) {
            if (!RetichatBridge.transportHasPath(nodeHash)) continue
            if (destinations.containsKey(nodeHash.toHex())) continue
            val label = if (nodeHash.toHex() == effectivePropHex) "lxmf.propagation" else "propagation.cached"
            appendDestination(label, nodeHash)
        }

        if (destinations.isEmpty()) {
            refreshRfedNodeStatus()
            return
        }

        scope.launch(Dispatchers.IO) {
            essentialRequestMutex.withLock {
                fun seedRfedServiceRoutesIfPossible() {
                    for ((_, sourceHash) in rfedCloneSources) {
                        if (!RetichatBridge.transportHasPath(sourceHash) ||
                            !RetichatBridge.transportIsPathVerifiedThisSession(sourceHash) ||
                            !RetichatBridge.transportIdentityKnown(sourceHash)
                        ) {
                            continue
                        }
                        for ((_, targetHash) in rfedServiceTargets) {
                            val hasPath = RetichatBridge.transportHasPath(targetHash)
                            val hasFreshPath = RetichatBridge.transportIsPathVerifiedThisSession(targetHash)
                            val hasIdentity = RetichatBridge.transportIdentityKnown(targetHash)
                            if (hasPath && hasFreshPath && hasIdentity) continue
                            RetichatBridge.transportClonePathAndIdentity(sourceHash, targetHash)
                        }
                    }
                }

                seedRfedServiceRoutesIfPossible()

                val requested = mutableListOf<PendingEssential>()
                for ((_, pair) in destinations) {
                    val (name, hash) = pair
                    val hasPath = RetichatBridge.transportHasPath(hash)
                    val hasFreshPath = RetichatBridge.transportIsPathVerifiedThisSession(hash)
                    val hasIdentity = RetichatBridge.transportIdentityKnown(hash)
                    val needsPath = !hasPath || !hasFreshPath
                    val needsIdentity = !hasIdentity
                    if (needsPath || needsIdentity) {
                        RetichatBridge.transportRequestPath(hash)
                        requested += PendingEssential(hash, "$name(${hash.toHex().take(8)})", needsPath, needsIdentity)
                    }
                }

                if (requested.isEmpty()) {
                    refreshRfedNodeStatus()
                    return@withLock
                }

                val deadline = System.currentTimeMillis() + 20_000L
                var resolvedAny = false
                while (System.currentTimeMillis() < deadline) {
                    seedRfedServiceRoutesIfPossible()
                    val resolvedHashes = mutableListOf<String>()
                    val iterator = requested.iterator()
                    while (iterator.hasNext()) {
                        val pending = iterator.next()
                        val pathReady = !pending.needsPath || (
                            RetichatBridge.transportHasPath(pending.hash) &&
                                RetichatBridge.transportIsPathVerifiedThisSession(pending.hash)
                            )
                        val identityReady = !pending.needsIdentity || RetichatBridge.transportIdentityKnown(pending.hash)
                        if (pathReady && identityReady) {
                            resolvedAny = true
                            resolvedHashes += pending.hash.toHex()
                            iterator.remove()
                        }
                    }
                    if (resolvedHashes.isNotEmpty()) {
                        val handlers = stateMutex.withLock {
                            resolvedHashes.mapNotNull { essentialReadyHandlers[it] }
                        }
                        handlers.forEach { handler ->
                            scope.launch { handler() }
                        }
                    }
                    refreshRfedNodeStatus()
                    if (requested.isEmpty()) break
                    delay(500)
                }

                if (resolvedAny) {
                    RetichatBridge.transportSavePaths()
                } else if (requested.isNotEmpty()) {
                    Log.d(TAG, "requestEssentialPaths: unresolved=${requested.joinToString { it.label }}")
                }
                refreshRfedNodeStatus()
            }
        }
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
