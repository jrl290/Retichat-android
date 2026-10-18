package com.newendian.retichat.service

import com.newendian.retichat.data.repository.PropagationFallbacks

/**
 * The DIRECT sends of the Group envelopes in flight (posts, relays, controls):
 * when each one's propagated copy starts, and when its handle is released.
 *
 * The copy starts as a DM's does ([PropagationFallbacks]): on the router's
 * 0x10, or when the DIRECT send FAILED, was REJECTED or CANCELLED first, once
 * per envelope; and a report that comes before [track] (0x10 at once, when the
 * link is down) is held and replayed. Until 2026-10-18 only a 0x10 that came
 * after [track] started it, so an envelope whose DIRECT send failed first, or
 * whose 0x10 came first, reached no offline member (L72).
 *
 * The copy is cloned from the DIRECT handle, and the router can report 0x10
 * and FAILED in one pass: a send that ends before its copy is cloned leaves
 * its handle for [cloned] to release. The copy has the DIRECT message's hash,
 * so its own SENT or FAILED comes under it; once the DIRECT send has ended
 * here, those are no longer an envelope's. Thread-safe: the router's callback
 * thread reports.
 *
 * Every envelope of a fan-out is followed at once, so [capacity] is well
 * above a group's size times the posts in flight.
 */
internal class GroupPropagationFallbacks(capacity: Int = 4096) {
    /**
     * What the caller does now: clone [copyFrom]'s propagated copy (off the
     * router's callback thread), and release [release]. At most one is set.
     */
    data class Actions(val copyFrom: Long? = null, val release: Long? = null)

    private val fallbacks = PropagationFallbacks(capacity)
    private val handles = HashMap<String, Long>()
    private val cloning = HashSet<Long>()
    private val releaseAfterClone = HashSet<Long>()

    /**
     * Follow the DIRECT send [hashHex] of [handle], and replay what the router
     * reported for it before now.
     */
    @Synchronized
    fun track(hashHex: String, handle: Long): Actions {
        handles[hashHex] = handle
        val send = PropagationFallbacks.Send(messageId = hashHex, directHandle = handle, bubble = false)
        return settle(hashHex, handle, fallbacks.track(hashHex, send))
    }

    /**
     * The router reported [state] for [hashHex]. Null when that is no Group
     * envelope's DIRECT send in flight.
     */
    @Synchronized
    fun onState(hashHex: String, state: Int): Actions? {
        val copy = fallbacks.onState(hashHex, state)
        val handle = handles[hashHex] ?: return null
        return settle(hashHex, handle, copy)
    }

    /**
     * [handle]'s copy was cloned, or given up. True when its send ended
     * meanwhile: the caller releases the handle now.
     */
    @Synchronized
    fun cloned(handle: Long): Boolean {
        cloning.remove(handle)
        return releaseAfterClone.remove(handle)
    }

    private fun settle(hashHex: String, handle: Long, copy: PropagationFallbacks.Send?): Actions {
        if (copy != null) cloning.add(handle)
        if (fallbacks.isTracked(hashHex)) return Actions(copyFrom = copy?.directHandle)
        handles.remove(hashHex)
        if (handle in cloning) {
            releaseAfterClone.add(handle)
            return Actions(copyFrom = copy?.directHandle)
        }
        return Actions(release = handle)
    }
}
