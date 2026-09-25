package com.newendian.retichat.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.newendian.retichat.RetichatApp
import com.newendian.retichat.bridge.RetichatBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Short-lived stack-spin triggered by an inbound FCM push (or by the user
 * tapping a message-list refresh action).
 *
 * Lifecycle:
 *   1. [StackRuntime.acquire] — bring the Reticulum stack up if not already.
 *      A cold start produces no up-edge announce: what reaches the network
 *      is the first announce at start (the app's own, or the publish
 *      daemon's first refresh sweep), which starts the 30-min period per
 *      interface; later up-edges inside that period stay quiet.
 *   2. Pull the per-channel deferred queue once.
 *   3. Run a propagation poll for LXMF.
 *   4. [StackRuntime.release] — exactly once, on every path. The stack
 *      stays up (D7): a wake finishing never stops it.
 *
 * Total budget ~25 s so we stay inside the system's expedited-work window.
 * No getForegroundInfo: minSdk is 31, and WorkManager 2.9.1 asks for it only
 * below 31 (WorkForegroundRunnable); from 31 an expedited job is a JobInfo
 * with setExpedited(true).
 */
class WakeWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val TAG = "WakeWorker"
        private const val BUDGET_MS = 25_000L

        /** One wake runs at a time; see [enqueue]. */
        internal const val UNIQUE_WORK_NAME = "retichat.wake"

        /**
         * Enqueue an expedited one-shot wake. Safe to call from FCM or UI.
         * Unique with KEEP: a push while a wake is pending or running adds
         * nothing, so two workers never catch up at once.
         *
         * What KEEP drops, until C4's owedAgain: a push for a message the
         * running wake has already passed waits for the next trigger; and
         * while a wake waits as a regular job (the expedited quota was
         * spent, so WorkManager scheduled it unexpedited, and Doze or the
         * standby bucket can defer it), later pushes get no expedited run
         * of their own.
         */
        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<WakeWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, req)
        }

        /**
         * One wake: hold the stack for [catchUp], within [budgetMs]. Returns
         * false when the budget ran out. The hold's one release is
         * [holdingStack]'s finally, which runs when the budget cancels
         * [catchUp]; until 2026-09-25 the budget branch released as well,
         * giving back a hold another holder (the app on screen) owned.
         */
        internal suspend fun wake(
            budgetMs: Long,
            acquire: suspend () -> Boolean,
            release: () -> Unit,
            catchUp: suspend (ready: Boolean) -> Unit,
        ): Boolean = withTimeoutOrNull(budgetMs) {
            holdingStack(acquire, release, catchUp)
            true
        } ?: false
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as? RetichatApp ?: return Result.success()
        Log.i(TAG, "wake $id: start")
        try {
            val inBudget = wake(
                BUDGET_MS,
                acquire = { StackRuntime.acquire(applicationContext) },
                release = StackRuntime::release,
            ) { ok ->
                if (!ok) {
                    Log.w(TAG, "StackRuntime.acquire failed — bailing out")
                    return@wake
                }

                // rfed.delivery re-announce is handled by Transport's publish daemon.
                // A cold start produces no up-edge announce; the announce at stack
                // start is what reaches the network, and up-edges are held to the
                // 30-min period per interface — no manual one-shot here.

                // Per-channel pull (drain one page each).
                val channels = app.database.channelDao().activeChannels()
                for (channel in channels) {
                    runCatching {
                        val (n, more) = app.rfedChannelClient.pullDeferred(channel)
                        Log.d(TAG, "PULL ${channel.channelName}: drained=$n more=$more")
                    }.onFailure { Log.w(TAG, "pullDeferred failed for ${channel.channelName}", it) }
                }

                // LXMF propagation sync.
                // Force a fresh propagation app-link ACTIVE edge here so a
                // wake-triggered pull does not reuse a stale held link from
                // before the transport reconnect completed.
                if (DistroManager.hasDistro) {
                    runCatching { RfedDistroClient.pull(applicationContext) }
                        .onFailure { Log.w(TAG, "distro pull failed", it) }
                }
                PropagationSync.runOnce(applicationContext, requireFreshTransport = true)
            }
            if (!inBudget) Log.w(TAG, "WakeWorker exceeded ${BUDGET_MS}ms budget")
        } finally {
            // Also when WorkManager stops the worker or the catch-up throws,
            // so every start in the log has its end.
            Log.i(TAG, "wake $id: end")
        }
        return Result.success()
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
