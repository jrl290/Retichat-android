package com.newendian.retichat.service

import android.content.Context
import android.util.Log
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.AnnounceCallback
import com.newendian.retichat.bridge.MessageCallback
import com.newendian.retichat.bridge.MessageStateCallback
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.bridge.RfedBlobCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owner of the Reticulum + LXMF + RFed-delivery handles.
 *
 * Replaces the old [ReticulumService]/[PersistentConnectionService] pair.
 * The stack is brought up the first time a caller [acquire]s and lives for
 * the life of the process (D7, 2026-09-25). Coming back on screen does not
 * re-initialize it: a shutdown resets announce history (Reticulum-rust
 * ffi.rs), so every start after one re-announced every destination on every
 * interface. Only a Settings [restart], a user action, stops it. In the
 * background the cached-apps freezer stops its threads where the device has
 * the freezer on; not every device at minSdk 31 does, and there a cached
 * process keeps its interfaces up until it dies (the 30 s grace shutdown
 * used to stop them).
 *
 * Holders are counted, each acquire given back by exactly one release; the
 * count says who is using the stack and a release nobody took is logged. It
 * never stops the stack:
 *   - the app while it is on screen → [ForegroundHold] on ProcessLifecycleOwner
 *   - [WakeWorker]                  → for one wake
 *   - a send or FCM token refresh that finds the stack down → [holding]
 *
 * No foreground service, no persistent notification.
 */
object StackRuntime {

    private const val TAG = "StackRuntime"

    private val refCount = AtomicInteger(0)
    private val initLock = Mutex()

    /** Blocks waiters on the very first acquire until the stack is ready. */
    @Volatile private var readyDeferred: CompletableDeferred<Boolean>? = null

    @Volatile var identityHandle: Long = 0L
        private set
    @Volatile var routerHandle: Long = 0L
        private set
    @Volatile var destHandle: Long = 0L
        private set
    @Volatile var selfDestHash: ByteArray = ByteArray(0)
        private set

    @Volatile var isReady: Boolean = false
        private set

    /**
     * Take a hold and start the stack if it is not running. Suspends until
     * it is ready. The hold is counted first, before anything can suspend or
     * throw, so a caller owes the release from the moment it calls this; use
     * [holding], which pays it on every path.
     */
    suspend fun acquire(context: Context): Boolean {
        countAcquire()
        return start(context)
    }

    /**
     * [acquire] for a lifecycle callback, which cannot suspend: the hold is
     * counted on the caller's thread before this returns, so the matching
     * [release] can never run first (§5). The start runs on the app scope.
     */
    fun acquireFromCallback(context: Context) {
        countAcquire()
        val app = context.applicationContext as RetichatApp
        app.applicationScope.launch { start(app) }
    }

    /** Hold the stack for [block], told whether it is ready; released once on every path. */
    suspend fun <T> holding(context: Context, block: suspend (ready: Boolean) -> T): T =
        holdingStack({ acquire(context) }, ::release, block)

    private fun countAcquire() {
        val newCount = refCount.incrementAndGet()
        Log.d(TAG, "acquire: refCount=$newCount")
    }

    private suspend fun start(context: Context): Boolean {
        val ready = startIfNeeded(context.applicationContext)
        if (ready) {
            (context.applicationContext as? RetichatApp)?.onStackReadyWhileForeground()
        }
        return ready
    }

    /**
     * Give a hold back. The stack stays up at zero (D7): no holder's release,
     * a WakeWorker's included, can stop it under a visible Activity.
     */
    fun release() {
        val before = refCount.getAndUpdate { if (it > 0) it - 1 else 0 }
        if (before == 0) {
            // A holder released twice, or released a hold it never took.
            Log.e(TAG, "release: refCount=0 — a release with no hold to give back")
            return
        }
        Log.d(TAG, "release: refCount=${before - 1}")
    }

    /** A bootstrap is running: its ready signal (onStackReady) will come. */
    val isStarting: Boolean
        get() = readyDeferred?.isCompleted == false

    /** Wait up to [timeoutMs] for the stack to be fully ready. */
    suspend fun awaitReady(timeoutMs: Long = 30_000L): Boolean {
        if (isReady) return true
        val d = readyDeferred ?: return false
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (d.isCompleted) return d.getCompleted()
            delay(100)
        }
        return false
    }

    // ---- Internal bootstrap (idempotent) ----

    private suspend fun startIfNeeded(context: Context): Boolean {
        initLock.withLock {
            if (isReady && identityHandle != 0L && routerHandle != 0L) return true

            val app = (context.applicationContext as RetichatApp)
            val deferred = CompletableDeferred<Boolean>()
            readyDeferred = deferred

            val ok = try {
                bootstrap(app)
            } catch (t: Throwable) {
                Log.e(TAG, "Bootstrap threw", t)
                false
            }
            isReady = ok
            deferred.complete(ok)
            // Initialization has finished: messages queued while it ran go
            // out now, after the message-state callback and
            // ConnectionStateManager are registered (§5).
            if (ok) app.repository.onStackReady()
            return ok
        }
    }

    private suspend fun bootstrap(app: RetichatApp): Boolean {
        if (!RetichatBridge.isLoaded) {
            app.updateServiceState(error = "Native library not loaded")
            Log.w(TAG, "Native library not loaded")
            return false
        }

        val configDir = File(app.filesDir, "reticulum").also { it.mkdirs() }

        // Read enabled interfaces; if none are configured, inject the same
        // three invisible fallback backbones that iOS uses. They are probed
        // once per start, so with D7 once per process: a set padded while
        // offline, or a backbone that dies later, stays until a Settings
        // restart or process death.
        var interfaces = app.database.interfaceConfigDao().enabledInterfaces()
        val useDefault = interfaces.isEmpty() && UserPreferences.isDefaultTcpEnabled(app)
        if (useDefault) {
            val endpoints = DefaultEndpointManager.selectFallbackEndpoints()
            Log.i(
                TAG,
                "No interfaces configured — default backbones " +
                    endpoints.joinToString { "${it.first}:${it.second}" },
            )
            interfaces = DefaultEndpointManager.fallbackInterfaceConfigs(endpoints)
        }
        writeReticulumConfig(configDir, interfaces)

        if (!RetichatBridge.init(configDir.absolutePath)) {
            val err = RetichatBridge.lastError() ?: "unknown"
            if (err.contains("already", ignoreCase = true)) {
                Log.i(TAG, "Reticulum already initialised — continuing")
            } else {
                app.updateServiceState(error = "Init failed: $err")
                Log.e(TAG, "Init failed: $err")
                return false
            }
        }

        // Identity
        val idFile = File(app.filesDir, "identity")
        identityHandle = if (idFile.exists()) {
            RetichatBridge.identityFromFile(idFile.absolutePath)
        } else {
            val h = RetichatBridge.identityCreate()
            if (h != 0L) RetichatBridge.identityToFile(h, idFile.absolutePath)
            h
        }
        if (identityHandle == 0L) {
            app.updateServiceState(error = "Identity failed: ${RetichatBridge.lastError()}")
            return false
        }

        // LXMRouter
        val storagePath = File(app.filesDir, "lxmf_storage").also { it.mkdirs() }
        routerHandle = RetichatBridge.routerCreate(identityHandle, storagePath.absolutePath)
        if (routerHandle == 0L) {
            app.updateServiceState(error = "Router failed: ${RetichatBridge.lastError()}")
            return false
        }

        val displayName = UserPreferences.getDisplayName(app)
        destHandle = RetichatBridge.routerRegisterDelivery(routerHandle, identityHandle, displayName)

        selfDestHash = RetichatBridge.destinationHash(identityHandle, "lxmf", "delivery")
            ?: ByteArray(0)

        // The distro identity, if this device holds one, is the identity the app
        // sends from — load it before anything can send. Queued messages go out
        // once bootstrap has finished (ChatRepository.onStackReady), and the
        // flush picks its source with DistroManager.sendingIdentity: a distro
        // loaded later let a queued message go out from the device address
        // with no RFed SPEC §17.11 sent-copy. Loaded before repo.configure(),
        // the first thing to hand out the router. It needs only identity
        // handles (native library, above), not the router or the network.
        runCatching { DistroManager.init(app) }
            .onFailure { Log.e(TAG, "DistroManager.init failed", it) }

        // Wire up repository
        val repo = app.repository
        repo.configure(selfDestHash, routerHandle, identityHandle)

        // Seed core delivery privacy settings from persisted preferences
        repo.primeCoreDeliveryPrivacy()

        RetichatBridge.routerSetDeliveryCallback(routerHandle, object : MessageCallback {
            override fun onMessage(
                hash: ByteArray, srcHash: ByteArray, destHash: ByteArray,
                title: String, content: String, timestamp: Double, signatureValid: Boolean,
                fieldsRaw: ByteArray,
            ) {
                repo.onMessageReceived(
                    hash, srcHash, destHash, title, content, timestamp, signatureValid, fieldsRaw,
                )
            }
        })

        RetichatBridge.routerSetAnnounceCallback(routerHandle, object : AnnounceCallback {
            override fun onAnnounce(destHash: ByteArray, displayName: String?) {
                repo.onAnnounceReceived(destHash, displayName)
            }
        })

        RetichatBridge.routerSetMessageStateCallback(routerHandle, object : MessageStateCallback {
            override fun onState(hash: ByteArray, state: Int) {
                repo.onMessageState(hash, state)
            }
        })

        if (UserPreferences.isDropAnnouncesEnabled(app)) {
            RetichatBridge.setDropAnnounces(true)
        }

        if (selfDestHash.isNotEmpty()) {
            // Hand the delivery destination off to Transport's auto-announce
            // daemon: it will announce immediately, on every interface
            // false→true online transition, and every 30 minutes thereafter,
            // each held per interface to one announce per 30 minutes.
            // Replaces the old "announce once at startup + hope" pattern.
            RetichatBridge.transportPublishDestination(selfDestHash, 30.0 * 60.0)
        }

        // Start the RFed delivery callback so channel/group blobs are dispatched
        // (the distro identity was loaded before repo.configure() above).
        RetichatBridge.rfedDeliveryStart(identityHandle, object : RfedBlobCallback {
            override fun onBlob(blob: ByteArray) {
                Log.i(TAG, "rfed.delivery blob received: ${blob.size} bytes")
                app.applicationScope.launch(Dispatchers.IO) {
                    runCatching { app.rfedChannelClient.dispatchInboundBlob(blob) }
                        .onFailure { Log.e(TAG, "dispatchInboundBlob failed", it) }
                }
            }
        })

        // rfed.delivery is now auto-announced by Transport's publish daemon
        // (registered inside rfedDeliveryStart above via Transport::publish_destination).
        // No manual announce call needed here.

        // Hand the router to ConnectionStateManager so it can register the
        // APP_LINK status callback, the network-change trigger, and pre-open
        // the rfed.channel link.  MUST happen before any async work that
        // calls ConnectionStateManager.appLinkSend (e.g. the persisted
        // channel re-subscribe below) — otherwise routerHandle==0 and the
        // first wave of sends fail with "APP_LINK not ACTIVE".
        ConnectionStateManager.register(app, routerHandle)

        // Re-register per-channel rfed.notify subscriptions so push wakeups resume
        // after process restart (mirrors iOS resubscribePersistedChannels).
        app.applicationScope.launch(Dispatchers.IO) {
            runCatching { app.rfedChannelClient.reregisterChannelPushOnStart() }
                .onFailure { Log.e(TAG, "reregisterChannelPushOnStart failed", it) }
            // Defer /rfed/subscribe for every persisted channel until the
            // rfed.channel APP_LINK reaches ACTIVE — see RfedChannelClient
            // KDoc and DESIGN_PRINCIPLES.md §1.  Calling resubscribe eagerly
            // races the link establishment and trips the 5 s assertion on
            // cold start; the right shape is to defer the send until the
            // link is observed up.  No timeout, no retry, no fail-state.
            // // NEVER REMOVE EVER — see DESIGN_PRINCIPLES.md §1
            runCatching { app.rfedChannelClient.scheduleResubscribeOnRfedChannelActive() }
                .onFailure { Log.e(TAG, "scheduleResubscribeOnRfedChannelActive failed", it) }
        }

        val hashHex = selfDestHash.joinToString("") { "%02x".format(it) }
        app.updateServiceState(
            isInitialized = true,
            identityHashHex = hashHex,
            interfaceCount = interfaces.size,
        )

        // Register the FCM bridge token before rfed.notify so the relay path
        // is live before we ask rfed to target it. Event-driven AppLinks only,
        // no app-level retries.
        try {
            FcmTokenRegistrar.registerIfNeeded(app, selfDestHash)
        } catch (t: Throwable) {
            Log.e(TAG, "FcmTokenRegistrar.registerIfNeeded failed", t)
        }

        // Register the rfed.notify wakeup relay (no-op if not configured).
        // Driven by the rfed.notify APP_LINK status callback — single shot,
        // no app-level retries (DESIGN_PRINCIPLES.md §2).
        if (identityHandle != 0L) {
            RfedNotifyRegistrar.registerIfNeeded(app, identityHandle)
        }
        // Distro (RFed SPEC §17): register this device for the shared identity
        // and have RFed announce it. No-op when no distro is loaded.
        runCatching { RfedDistroClient.registerIfNeeded(app) }
            .onFailure { Log.e(TAG, "RfedDistroClient.registerIfNeeded failed", it) }

        Log.i(TAG, "StackRuntime ready — dest=$hashHex, ${interfaces.size} interface(s)")
        return true
    }

    private fun shutdownNow(app: RetichatApp) {
        Log.i(TAG, "Shutting down stack (refCount=${refCount.get()})")
        isReady = false
        readyDeferred = null
        // The next start registers the distro again (the node may have changed).
        RfedDistroClient.onStackStopped()

        ConnectionStateManager.unregister()

        app.repository.configure(ByteArray(0), 0L, 0L)

        runCatching { RetichatBridge.rfedDeliveryStop() }

        if (selfDestHash.isNotEmpty()) {
            runCatching { RetichatBridge.transportUnpublishDestination(selfDestHash) }
        }

        if (routerHandle != 0L) {
            runCatching { RetichatBridge.routerDestroy(routerHandle) }
            routerHandle = 0L
        }
        if (identityHandle != 0L) {
            runCatching { RetichatBridge.identityDestroy(identityHandle) }
            identityHandle = 0L
        }
        destHandle = 0L
        selfDestHash = ByteArray(0)
        runCatching { RetichatBridge.shutdown() }
        app.updateServiceState()
    }

    /**
     * Settings "Restart": tear the stack down and bring it straight back up so
     * new interface / RFed settings take effect. Holders keep their references
     * (the app on screen still owns one), so the count is untouched. The only
     * stop in the process's life (D7).
     *
     * Until 2026-09-24 the button called forceShutdown (removed 2026-09-25),
     * which zeroed the count and waited for some later acquire() to
     * re-bootstrap; with the app already in the foreground nothing acquired
     * again and the status stayed "Not started".
     */
    suspend fun restart(context: Context): Boolean {
        val app = context.applicationContext as RetichatApp
        initLock.withLock { shutdownNow(app) }
        return startIfNeeded(app)
    }
}
