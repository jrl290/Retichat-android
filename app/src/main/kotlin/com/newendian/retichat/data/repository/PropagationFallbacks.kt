package com.newendian.retichat.data.repository

import com.newendian.retichat.bridge.RetichatBridge.MessageState

/**
 * When a 1:1 message's propagated copy starts, decided by the router's reports
 * on its DIRECT send, as on iOS (ChatRepository.swift handleMessageState).
 *
 * The router asks for the copy with PROP_FALLBACK_REQUESTED (0x10): AppLinks
 * Timer P, 5 s without a proof, or at once when the link status is
 * DISCONNECTED. The DIRECT send keeps running beside the copy. A DIRECT send
 * that fails (FAILED, REJECTED, CANCELLED) before any 0x10 starts the copy
 * then. One copy per message, whichever report comes first. Until 2026-09-24
 * Android ran its own 5 s timer instead and skipped the copy when the message
 * had already failed, so a DIRECT send that failed early was never propagated.
 *
 * The router can report 0x10 before the send's hash is known here (at once,
 * when the link is down), so a report for a hash not tracked yet is held and
 * replayed by [track], as iOS earlyMessageStates. Everything is bounded: the
 * oldest entries go first. Thread-safe: the router's callback thread reports.
 */
internal class PropagationFallbacks(private val capacity: Int = 256) {
    /**
     * A DIRECT send, with what its propagated copy needs. Attachments are not
     * held here: they are on disk with the message (saveOutboundAttachments),
     * and a send the router never reports on again (the stack stopped under
     * it) would otherwise keep their bytes until the capacity pushed it out.
     */
    class Send(
        val messageId: String,
        val destHash: ByteArray,
        val content: String,
    )

    private val tracked = bounded<Send>()
    private val early = bounded<MutableList<Int>>()
    private val started = bounded<Unit>()

    private fun <V> bounded() = object : LinkedHashMap<String, V>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>) = size > capacity
    }

    /**
     * Follow the DIRECT send [hashHex] and replay what the router reported for
     * it before now. Returns the send whose propagated copy starts now, or null.
     */
    @Synchronized
    fun track(hashHex: String, send: Send): Send? {
        tracked[hashHex] = send
        var start: Send? = null
        early.remove(hashHex)?.forEach { state -> decide(hashHex, state)?.let { start = it } }
        return start
    }

    /**
     * The router reported [state] for [hashHex]. Returns the send whose
     * propagated copy starts now, or null.
     */
    @Synchronized
    fun onState(hashHex: String, state: Int): Send? {
        if (hashHex in tracked) return decide(hashHex, state)
        when (state) {
            MessageState.PROP_FALLBACK_REQUESTED,
            MessageState.FAILED, MessageState.REJECTED, MessageState.CANCELLED ->
                early.getOrPut(hashHex) { mutableListOf() }.add(state)
            // A send that already succeeded needs no copy: replaying only the
            // success makes track() let go of it again at once.
            MessageState.SENT, MessageState.DELIVERED -> early[hashHex] = mutableListOf(state)
        }
        return null
    }

    /**
     * The router is gone (the stack stopped): nothing tracked will be
     * reported on again.
     */
    @Synchronized
    fun clear() {
        tracked.clear()
        early.clear()
    }

    /** For tests: whether [hashHex] is still followed. */
    @Synchronized
    internal fun isTracked(hashHex: String): Boolean = hashHex in tracked

    /**
     * The state poll read [state] off the DIRECT handle [hashHex]: the same
     * decision as the router's report, whichever comes first. Nothing is held,
     * because the poll starts after [track]; an untracked hash has ended.
     */
    @Synchronized
    fun onPolledState(hashHex: String, state: Int): Send? =
        if (hashHex in tracked) decide(hashHex, state) else null

    private fun decide(hashHex: String, state: Int): Send? {
        val send = tracked[hashHex] ?: return null
        return when (state) {
            // The DIRECT send keeps running, so it stays tracked.
            MessageState.PROP_FALLBACK_REQUESTED -> startOnce(send)
            MessageState.FAILED, MessageState.REJECTED, MessageState.CANCELLED -> {
                tracked.remove(hashHex)
                startOnce(send)
            }
            MessageState.SENT, MessageState.DELIVERED -> {
                tracked.remove(hashHex)
                null
            }
            else -> null
        }
    }

    private fun startOnce(send: Send): Send? =
        if (started.put(send.messageId, Unit) == null) send else null
}
