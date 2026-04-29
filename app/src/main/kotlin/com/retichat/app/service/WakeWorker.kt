package com.retichat.app.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.retichat.app.RetichatApp
import com.retichat.app.bridge.RetichatBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Short-lived stack-spin triggered by an inbound FCM push (or by the user
 * tapping a message-list refresh action).
 *
 * Lifecycle:
 *   1. [StackRuntime.acquire] — bring the Reticulum stack up if not already.
 *   2. Announce rfed.delivery so the server flushes deferred blobs.
 *   3. Pull the per-channel deferred queue once.
 *   4. Run a propagation poll for LXMF.
 *   5. [StackRuntime.release] — stack tears down after the grace period
 *      unless an Activity is in the foreground.
 *
 * Total budget ~25 s so we stay inside the system's expedited-work window.
 */
class WakeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "WakeWorker"
        private const val BUDGET_MS = 25_000L

        /** Enqueue an expedited one-shot wake. Safe to call from FCM or UI. */
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<WakeWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueue(req)
        }
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as? RetichatApp ?: return Result.success()
        return withTimeoutOrNull(BUDGET_MS) {
            try {
                val ok = StackRuntime.acquire(applicationContext)
                if (!ok) {
                    Log.w(TAG, "StackRuntime.acquire failed — bailing out")
                    return@withTimeoutOrNull Result.success()
                }

                // Step 1: ask the rfed delivery endpoint to flush deferred blobs.
                runCatching { RetichatBridge.rfedDeliveryAnnounce() }
                    .onFailure { Log.w(TAG, "rfedDeliveryAnnounce failed", it) }

                // Step 2: per-channel pull (drain one page each).
                val channels = app.database.channelDao().activeChannels()
                for (channel in channels) {
                    runCatching {
                        val (n, more) = app.rfedChannelClient.pullDeferred(channel)
                        Log.d(TAG, "PULL ${channel.channelName}: drained=$n more=$more")
                    }.onFailure { Log.w(TAG, "pullDeferred failed for ${channel.channelName}", it) }
                }

                // Step 3: LXMF propagation sync.
                PropagationSync.runOnce(applicationContext)

                Result.success()
            } finally {
                StackRuntime.release()
            }
        } ?: run {
            Log.w(TAG, "WakeWorker exceeded ${BUDGET_MS}ms budget")
            StackRuntime.release()
            Result.success()
        }
    }

    /**
     * One-shot propagation poll — delegates to [PropagationSync] so the
     * foreground 5-min timer and the FCM/expedited-worker path share the
     * same logic.
     */
    private suspend fun runPropagationOnce(app: RetichatApp) {
        PropagationSync.runOnce(applicationContext)
    }
}
