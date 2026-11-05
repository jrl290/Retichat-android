package com.newendian.retichat.service

import android.util.Log
import com.newendian.retichat.RetichatApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * What RFed holds for this device, collected in one order (James,
 * 2026-11-05: "distro and propagation should be first"): the distro queue,
 * then the messages waiting on the propagation node, then the channels with
 * push on, the ones RFed wakes this device for (RfedNotifyRegistrar). The
 * other channels wait until they are opened.
 *
 * It is how a device that holds no link to RFed ([RfedLink.holdsNoLink])
 * collects: on a wake from RFed over the mesh (MeshWake), on each link the
 * device opens for its requests, and at the 6-hour check. The collection is
 * one run on rfed.link ([RfedLink.holding]): the link opens once for it and
 * closes when it ends.
 *
 * One collection at a time. A link that opens while one runs was opened by
 * it, and is covered by it; a wake while one runs may name a message stored
 * after its pulls passed, so the collection runs once more when it ends.
 */
object RfedCollect {
    private const val TAG = "RfedCollect"

    private val turns = Turns()

    /** Collect, unless a collection runs. [why] is for the log; [linkOpened]: asked by a link opening. */
    fun request(app: RetichatApp, why: String, linkOpened: Boolean = false) {
        if (!turns.ask(linkOpened)) return
        app.applicationScope.launch(Dispatchers.IO) {
            var reason = why
            try {
                do {
                    try {
                        collect(app, reason)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "collection failed", e)
                    }
                    reason = "a wake came while collecting"
                } while (turns.next())
            } catch (e: CancellationException) {
                turns.stopped()
                throw e
            }
        }
    }

    /** One collection at a time; a wake while one runs has it run once more. */
    internal class Turns {
        private var running = false
        private var again = false

        /** True: the caller collects. False: the running collection covers it, or runs again for it. */
        @Synchronized
        fun ask(linkOpened: Boolean): Boolean {
            if (running) {
                if (!linkOpened) again = true
                return false
            }
            running = true
            return true
        }

        /** Whether to collect again; when not, the collection has ended. */
        @Synchronized
        fun next(): Boolean {
            if (again) {
                again = false
                return true
            }
            running = false
            return false
        }

        /** The collection was cancelled. */
        @Synchronized
        fun stopped() {
            running = false
            again = false
        }
    }

    private suspend fun collect(app: RetichatApp, why: String) {
        val node = UserPreferences.getEffectiveRfedNodeIdentityHash(app)
        Log.i(TAG, "collecting ($why): the distro queue, then propagation, then the channels with push on")
        RfedLink.holding(node) {
            if (DistroManager.hasDistro) {
                try {
                    RfedDistroClient.pull(app)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "distro pull failed", e)
                }
            }
            try {
                PropagationSync.runOnce(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "propagation sync failed", e)
            }
            app.rfedChannelClient.pullPushChannels()
        }
    }
}
