package com.newendian.retichat.service

import android.content.Context
import android.util.Log
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.AnnounceCallback
import com.newendian.retichat.bridge.MessageCallback
import com.newendian.retichat.bridge.RetichatBridge
import com.newendian.retichat.bridge.RfedBlobCallback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Reference-counted owner of the Reticulum + LXMF + RFed-delivery handles.
 *
 * Replaces the old [ReticulumService]/[PersistentConnectionService] pair.
 * The stack is brought up the first time a caller [acquire]s and torn down
 * a few seconds after the final [release] (grace period prevents thrash
 * when the user backgrounds + foregrounds quickly).
 *
 * Lifecycle entry points:
 *   - [MainActivity.onStart]              → acquire / release on stop
 *   - [WakeWorker]                        → acquire while running, release when done
 *   - [PropagationPollWorker]             → (legacy) replaced by WakeWorker
 *
 * No foreground service, no persistent notification.
 */
object StackRuntime {

    private const val TAG = "StackRuntime"
    /** How long to keep the stack warm after the last release(). */
private const val GRACE_SHUTDOWN_MS = 30_000L  // 30s grace avoids stack teardown on brief background flaps

    private val refCount = AtomicInteger(0)
    private val initLock = Mutex()
    private var shutdownJob: Job? = null

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

    /** Increment the ref count. If first acquirer, start the stack. Suspends until ready. */
    suspend fun acquire(context: Context): Boolean {
        // Cancel any pending shutdown
        shutdownJob?.cancel()
        shutdownJob = null

        val newCount = refCount.incrementAndGet()
        Log.d(TAG, "acquire: refCount=$newCount")

        return startIfNeeded(context.applicationContext)
    }

    /** Synchronous variant for callers without a coroutine scope. */
    fun acquireBlocking(context: Context): Boolean = runBlocking { acquire(context) }

    /** Decrement the ref count. If it hits zero, schedule a graceful shutdown. */
    fun release() {
        val newCount = refCount.decrementAndGet()
        Log.d(TAG, "release: refCount=$newCount")
        if (newCount > 0) return
        if (newCount < 0) {
            refCount.set(0)
            return
        }

        // Schedule a delayed shutdown so a quick foreground/background flap
        // doesn't tear down + re-init the stack.
        val app = (RetichatApp.appInstance ?: return)
        shutdownJob = app.applicationScope.launch(Dispatchers.IO) {
            delay(GRACE_SHUTDOWN_MS)
            if (!isActive) return@launch
            if (refCount.get() == 0) {
                shutdownNow(app)
            }
        }
    }

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
        // three invisible fallback backbones that iOS uses.
        var interfaces = app.database.interfaceConfigDao().enabledInterfaces()
        val useDefault = interfaces.isEmpty() && UserPreferences.isDefaultTcpEnabled(app)
        if (useDefault) {
            val endpoints = DefaultEndpointManager.fallbackEndpoints()
            Log.i(
                TAG,
                "No interfaces configured — default backbones " +
                    endpoints.joinToString { "${it.first}:${it.second}" },
            )
            interfaces = DefaultEndpointManager.fallbackInterfaceConfigs()
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

        // Wire up repository
        val repo = app.repository
        repo.configure(selfDestHash, routerHandle, identityHandle)

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

        if (UserPreferences.isDropAnnouncesEnabled(app)) {
            RetichatBridge.setDropAnnounces(true)
        }

        if (selfDestHash.isNotEmpty()) {
            // Hand the delivery destination off to Transport's auto-announce
            // daemon: it will announce immediately, on every interface
            // false→true online transition, and every 30 minutes thereafter.
            // Replaces the old "announce once at startup + hope" pattern.
            RetichatBridge.transportPublishDestination(selfDestHash, 30.0 * 60.0)
        }

        // Start the RFed delivery callback so channel/group blobs are dispatched
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

        Log.i(TAG, "StackRuntime ready — dest=$hashHex, ${interfaces.size} interface(s)")
        return true
    }

    private fun shutdownNow(app: RetichatApp) {
        Log.i(TAG, "Shutting down stack (refCount=0)")
        isReady = false
        readyDeferred = null

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

    /** Force teardown ignoring ref count; only used by tests / settings restart. */
    fun forceShutdown(context: Context) {
        val app = context.applicationContext as RetichatApp
        refCount.set(0)
        shutdownJob?.cancel()
        shutdownJob = null
        shutdownNow(app)
    }
}
