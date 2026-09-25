package com.newendian.retichat.data.repository

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** One flush: snapshot the queue, then send each row from that snapshot. */
private typealias Flush = suspend (pending: suspend () -> List<String>, send: suspend (List<String>) -> Unit) -> Unit

/**
 * ChatRepository's offline-queue flush: two triggers (the stack's ready signal
 * and the network-available listener) overlapping must not send a queued
 * message, or its RFed SPEC §17.11 sent-copy, twice.
 */
class SerialFlushTest {
    /**
     * The first trigger is part-way through sending m1, which has not left
     * the queue yet (its DB write is a suspension away), when the second
     * fires. Returns every row sent, in order, and what is left queued.
     * Event-driven: no clock anywhere.
     */
    private fun overlappingTriggers(flush: Flush): Pair<List<String>, List<String>> = runBlocking {
        val queue = mutableListOf("m1", "m2")
        val sent = mutableListOf<String>()
        val firstMidSend = CompletableDeferred<Unit>()
        val firstMayFinish = CompletableDeferred<Unit>()

        val first = launch {
            flush({ queue.toList() }) { rows ->
                for (row in rows) {
                    sent += row
                    firstMidSend.complete(Unit)
                    firstMayFinish.await()
                    queue -= row
                }
            }
        }
        firstMidSend.await()
        val second = launch {
            flush({ queue.toList() }) { rows ->
                for (row in rows) {
                    sent += row
                    queue -= row
                }
            }
        }
        // Let the second trigger run as far as it can before the first ends.
        yield()
        firstMayFinish.complete(Unit)
        joinAll(first, second)
        sent to queue
    }

    @Test
    fun overlappingTriggersSendEachQueuedRowOnce() {
        val serial = SerialFlush()
        val (sent, left) = overlappingTriggers { pending, send -> serial.run(pending, send) }
        assertEquals(listOf("m1", "m2"), sent)
        assertTrue(left.isEmpty())
    }

    @Test
    fun withoutSerializingTheSameScenarioSendsTwice() {
        // What the flush did before: each trigger worked from its own snapshot.
        val (sent, _) = overlappingTriggers { pending, send -> send(pending()) }
        assertEquals(2, sent.count { it == "m1" })
    }

    @Test
    fun anEmptyQueueSendsNothing() = runBlocking {
        SerialFlush().run({ emptyList<String>() }) { fail("nothing is queued") }
    }
}
