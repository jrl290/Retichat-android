package com.retichat.app.service

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.retichat.app.RetichatApp
import com.retichat.app.bridge.AnnounceCallback
import com.retichat.app.bridge.MessageCallback
import com.retichat.app.bridge.RetichatBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * WorkManager periodic worker that polls propagation nodes for waiting
 * messages every 15 minutes — even when the screen is off and unplugged.
 *
 * On Android 12+ a BroadcastReceiver / WorkManager cannot call
 * `startService()` (BackgroundServiceStartNotAllowedException).
 * Therefore, when [ReticulumService] is not already running, the worker
 * performs a *headless* poll:  it boots the Rust Reticulum stack via JNI,
 * creates short-lived identity+router handles, polls the propagation
 * nodes, delivers any received messages through [ChatRepository], and
 * tears down.
 */
class PropagationPollWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "PropagationPollWorker"
        private const val WORK_NAME = "propagation_poll"
        private const val INTERVAL_MINUTES = 15L
        private const val MAX_RETRIES = 3
        private const val MONITOR_POLL_MS = 500L
        private const val MONITOR_TIMEOUT_MS = 60_000L

        /** Stay connected for 5 minutes after polling to catch DIRECT messages. */
        private const val LISTEN_WINDOW_MS = 5L * 60L * 1000L

        // ---- Idle backoff ----
        private const val PREFS_NAME = "propagation_poll_prefs"
        private const val PREF_CONSECUTIVE_EMPTY = "consecutive_empty_polls"
        private const val PREF_LAST_POLL_MS = "last_poll_epoch_ms"

        /** After this many consecutive empty polls, double the effective interval. */
        private const val BACKOFF_TIER_1 = 4   // 4 empties → 30 min
        /** After this many, quadruple it. */
        private const val BACKOFF_TIER_2 = 8   // 8 empties → 60 min

        private const val EFFECTIVE_INTERVAL_1_MS = 30L * 60L * 1000L
        private const val EFFECTIVE_INTERVAL_2_MS = 60L * 60L * 1000L

        /**
         * Enqueue the periodic work. Safe to call multiple times — uses
         * KEEP policy so existing schedule is not replaced.
         */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<PropagationPollWorker>(
                INTERVAL_MINUTES, TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .build()

            // TODO: revert to KEEP after one release cycle — REPLACE forces
            //       the updated constraints (network+battery) to take effect.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            Log.i(TAG, "Periodic propagation poll enqueued/updated (every ${INTERVAL_MINUTES}min, network+battery)")
        }

        /** Cancel the periodic work. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Periodic propagation poll cancelled")
        }

        /**
         * Reset the idle backoff counter — call when the user opens the app
         * or when a message arrives, so the next WorkManager cycle does a
         * full poll regardless of prior idle history.
         */
        fun resetIdleBackoff(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(PREF_CONSECUTIVE_EMPTY, 0)
                .apply()
            Log.d(TAG, "Idle backoff counter reset")
        }
    }

    override suspend fun doWork(): Result {
        Log.i(TAG, "Propagation poll triggered by WorkManager")

        // If the service is already running, its init-time poll already ran
        // and the link keepalive keeps us reachable — skip.
        if (ReticulumService.isInitialised) {
            Log.i(TAG, "Service already running — skipping redundant poll")
            return Result.success()
        }

        // ---- Idle backoff: skip this cycle if we've had many empty polls ----
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val consecutiveEmpty = prefs.getInt(PREF_CONSECUTIVE_EMPTY, 0)
        val lastPollMs = prefs.getLong(PREF_LAST_POLL_MS, 0L)
        val now = System.currentTimeMillis()

        val effectiveIntervalMs = when {
            consecutiveEmpty >= BACKOFF_TIER_2 -> EFFECTIVE_INTERVAL_2_MS  // 60 min
            consecutiveEmpty >= BACKOFF_TIER_1 -> EFFECTIVE_INTERVAL_1_MS  // 30 min
            else -> 0L  // no backoff — always poll
        }

        if (effectiveIntervalMs > 0 && (now - lastPollMs) < effectiveIntervalMs) {
            Log.i(TAG, "Idle backoff: $consecutiveEmpty consecutive empties, " +
                "skipping (next poll in ${(effectiveIntervalMs - (now - lastPollMs)) / 1000}s)")
            return Result.success()
        }

        return withContext(Dispatchers.IO) { headlessPoll() }
    }

    // ------------------------------------------------------------------
    // Headless cycle:  init → identity → router → poll → cleanup
    // ------------------------------------------------------------------

    /** Tracks messages received during this poll cycle. */
    private val messagesReceived = AtomicInteger(0)

    private suspend fun headlessPoll(): Result {
        val app = applicationContext as? RetichatApp ?: return Result.failure()

        if (!RetichatBridge.isLoaded) {
            Log.w(TAG, "Native library not loaded — skipping")
            return Result.failure()
        }

        // 1.  Generate config & init Reticulum (idempotent)
        val configDir = File(applicationContext.filesDir, "reticulum").also { it.mkdirs() }
        var interfaces = app.database.interfaceConfigDao().enabledInterfaces()
        if (interfaces.isEmpty() && UserPreferences.isDefaultTcpEnabled(applicationContext)) {
            interfaces = listOf(DefaultEndpointManager.asInterfaceConfig())
        } else if (UserPreferences.isDefaultTcpEnabled(applicationContext)) {
            interfaces = interfaces + DefaultEndpointManager.asInterfaceConfig()
        }
        writeReticulumConfig(configDir, interfaces)

        if (!RetichatBridge.init(configDir.absolutePath)) {
            val err = RetichatBridge.lastError() ?: ""
            if (!err.contains("already", ignoreCase = true)) {
                Log.e(TAG, "Headless init failed: $err")
                return Result.retry()
            }
        }

        // 2.  Identity (load from disk — don't create; if none exists the
        //     user hasn't opened the app yet, so there's nothing to poll).
        val idFile = File(applicationContext.filesDir, "identity")
        if (!idFile.exists()) {
            Log.i(TAG, "No identity file yet — skipping poll")
            shutdownIfSafe()
            return Result.success()
        }
        val identityHandle = RetichatBridge.identityFromFile(idFile.absolutePath)
        if (identityHandle == 0L) {
            Log.e(TAG, "Failed to load identity: ${RetichatBridge.lastError()}")
            shutdownIfSafe()
            return Result.retry()
        }

        // 3.  Router + delivery callback
        val storagePath = File(applicationContext.filesDir, "lxmf_storage").also { it.mkdirs() }
        val routerHandle = RetichatBridge.routerCreate(identityHandle, storagePath.absolutePath)
        if (routerHandle == 0L) {
            Log.e(TAG, "Failed to create router: ${RetichatBridge.lastError()}")
            RetichatBridge.identityDestroy(identityHandle)
            shutdownIfSafe()
            return Result.retry()
        }

        val displayName = UserPreferences.getDisplayName(applicationContext)
        RetichatBridge.routerRegisterDelivery(routerHandle, identityHandle, displayName)

        val destHash = RetichatBridge.destinationHash(identityHandle, "lxmf", "delivery")
            ?: ByteArray(0)
        val repo = app.repository
        repo.configure(destHash, routerHandle, identityHandle)

        RetichatBridge.routerSetDeliveryCallback(routerHandle, object : MessageCallback {
            override fun onMessage(
                hash: ByteArray, srcHash: ByteArray, destHash: ByteArray,
                title: String, content: String, timestamp: Double, signatureValid: Boolean,
                fieldsRaw: ByteArray,
            ) {
                messagesReceived.incrementAndGet()
                repo.onMessageReceived(
                    hash, srcHash, destHash, title, content, timestamp, signatureValid,
                    fieldsRaw,
                )
            }
        })

        // Announce callback → update contact display names
        RetichatBridge.routerSetAnnounceCallback(routerHandle, object : AnnounceCallback {
            override fun onAnnounce(destHash: ByteArray, displayName: String?) {
                repo.onAnnounceReceived(destHash, displayName)
            }
        })

        // 4.  Relax keepalive — we only live ~5 min, no need for aggressive probes
        RetichatBridge.setKeepaliveInterval(360.0)

        // 5.  Let the network settle, then poll propagation nodes
        delay(3000)
        pollPropagationNodes(routerHandle, identityHandle)

        // 6.  Announce our delivery destination so DIRECT senders can reach us
        if (destHash.isNotEmpty()) {
            RetichatBridge.routerAnnounce(routerHandle, destHash)
            Log.i(TAG, "Headless: announced delivery destination")
        }

        // 7.  Stay connected for 5 minutes to receive any DIRECT messages
        //     that arrive while we're reachable.  Bail early if the
        //     foreground service starts (it takes over).
        val listenDeadline = System.currentTimeMillis() + LISTEN_WINDOW_MS
        Log.i(TAG, "Headless: listening for DIRECT messages for ${LISTEN_WINDOW_MS / 1000}s")
        while (System.currentTimeMillis() < listenDeadline) {
            if (ReticulumService.isInitialised) {
                Log.i(TAG, "Headless: service started \u2014 handing off")
                // Don't clean up \u2014 the service owns the Reticulum singleton now
                return Result.success()
            }
            delay(2000)
        }
        Log.i(TAG, "Headless: listen window elapsed")

        // 8.  Update idle backoff counter
        val received = messagesReceived.get()
        val prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prevEmpty = prefs.getInt(PREF_CONSECUTIVE_EMPTY, 0)
        val newEmpty = if (received > 0) 0 else prevEmpty + 1
        prefs.edit()
            .putInt(PREF_CONSECUTIVE_EMPTY, newEmpty)
            .putLong(PREF_LAST_POLL_MS, System.currentTimeMillis())
            .apply()
        Log.i(TAG, "Headless: received $received messages, consecutive empty=$newEmpty")

        // 9.  Cleanup \u2014 destroy our handles (don't leak FFI allocations)
        repo.configure(ByteArray(0), 0L, 0L)
        RetichatBridge.routerDestroy(routerHandle)
        RetichatBridge.identityDestroy(identityHandle)
        shutdownIfSafe()

        Log.i(TAG, "Headless poll complete")
        return Result.success()
    }

    /**
     * Only shut down the Reticulum singleton if the Service hasn't
     * started in the meantime (avoids pulling the rug from under it).
     */
    private fun shutdownIfSafe() {
        if (!ReticulumService.isInitialised) {
            RetichatBridge.shutdown()
        }
    }

    // ------------------------------------------------------------------
    // Propagation poll with node rotation (mirrors ReticulumService logic)
    // ------------------------------------------------------------------

    private suspend fun pollPropagationNodes(routerHandle: Long, identityHandle: Long) {
        val nodeManager = PropagationNodeManager()
        var retriesRemaining = MAX_RETRIES

        while (retriesRemaining > 0) {
            val nodeHash = nodeManager.primaryNode
            val nodeHex = nodeHash.joinToString("") { "%02x".format(it) }

            if (!RetichatBridge.routerSetPropagationNode(routerHandle, nodeHash)) {
                Log.e(TAG, "Set propagation node $nodeHex failed: ${RetichatBridge.lastError()}")
                nodeManager.reportFailure()
                retriesRemaining--
                continue
            }

            Log.i(TAG, "Headless: requesting messages from $nodeHex")
            if (!RetichatBridge.routerRequestMessages(routerHandle, identityHandle)) {
                Log.e(TAG, "Request messages failed: ${RetichatBridge.lastError()}")
                nodeManager.reportFailure()
                retriesRemaining--
                continue
            }

            // Wait for the state machine to reach a terminal state
            val deadline = System.currentTimeMillis() + MONITOR_TIMEOUT_MS
            var lastState = -1

            while (System.currentTimeMillis() < deadline) {
                // Bail if the Service came alive
                if (ReticulumService.isInitialised) {
                    Log.i(TAG, "Service started — handing off")
                    return
                }

                val state = RetichatBridge.routerGetPropagationState(routerHandle)
                if (state != lastState) {
                    Log.d(TAG, "Headless state: 0x${"%02X".format(state)}")
                    lastState = state
                }

                when {
                    state == RetichatBridge.PropagationState.PR_COMPLETE -> {
                        Log.i(TAG, "Headless sync complete from $nodeHex")
                        nodeManager.reportSuccess()
                        return
                    }
                    state == RetichatBridge.PropagationState.PR_IDLE && lastState > 0 -> {
                        Log.i(TAG, "Headless: returned to idle — done")
                        nodeManager.reportSuccess()
                        return
                    }
                    state >= 0xF0 -> {
                        Log.w(TAG, "Headless: propagation failed (0x${"%02X".format(state)}) from $nodeHex")
                        nodeManager.reportFailure()
                        retriesRemaining--
                        break // try next node
                    }
                }

                delay(MONITOR_POLL_MS)
            }

            // Timeout
            if (System.currentTimeMillis() >= deadline) {
                Log.w(TAG, "Headless: timed out polling $nodeHex")
                RetichatBridge.routerCancelPropagation(routerHandle)
                nodeManager.reportFailure()
                retriesRemaining--
            }
        }
    }
}
