package com.newendian.retichat.data.repository

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs flushes of a persisted send queue one at a time (ChatRepository's
 * offline queue). Its triggers can fire together: the stack's ready signal,
 * the NetworkMonitor on-available listener, and a send that queued while the
 * stack was starting. Each used to send from its own snapshot of the queue,
 * so a queued message and its RFed SPEC §17.11 sent-copy went out twice.
 *
 * [pending] is read under the lock: a trigger that arrives mid-flush waits
 * for that flush to end (its completion is the signal, §5; no timer, no
 * retry) and then reads the queue afresh, where the rows the first flush
 * sent no longer appear.
 */
internal class SerialFlush {
    private val lock = Mutex()

    suspend fun <R> run(pending: suspend () -> List<R>, send: suspend (List<R>) -> Unit) {
        lock.withLock {
            val rows = pending()
            if (rows.isNotEmpty()) send(rows)
        }
    }
}
