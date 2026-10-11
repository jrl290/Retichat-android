package com.newendian.retichat.data.repository

import com.newendian.retichat.data.db.entity.GroupArrivalHead

/**
 * The held arrivals' bounds (GROUPS-DISTRO-DESIGN.md §6.5): a group message or
 * a `post` group sync that reached this device through the distro for a group
 * not joined here, kept only when the group not being joined is the sole
 * reason it would be dropped, and replayed in arrival order when the group is
 * joined. Counts and bytes, never a clock. Pure logic: Room keeps the blobs
 * (`group_arrivals`), this decides what a new one does.
 */
object HeldArrivals {
    /** Blobs per device, in all. */
    const val TOTAL = 64
    /** Bytes per device, in all. */
    const val BYTES = 256 * 1024
    /** Blobs per group. */
    const val PER_GROUP = 16
    /** Blobs per LXMF source other than D (a `post` G, source D, counts against its group, not a source). */
    const val PER_SOURCE = 8
    /** Blobs for groups not held here, in all; such a blob never evicts one of a group held pending here. */
    const val NOT_HELD = 16
    /** A single blob over this (a post with an attachment) is never held. */
    const val BLOB_MAX = 64 * 1024

    /** A blob offered for holding. */
    data class Candidate(
        val groupId: String,
        val srcHex: String,
        val msgHash: String,
        /** A digest of the blob: a copy with another signature is another blob (the unwrap reports no signature bytes). */
        val blobDigest: String,
        val validated: Boolean,
        val isPostSync: Boolean,
        val size: Int,
    )

    sealed class Decision {
        /** Not held: why, for the log line naming the group, the source and the bound. */
        data class Refuse(val reason: String) : Decision()
        /** The same blob, or a validated copy of the same message, is held already. */
        data class Duplicate(val reason: String) : Decision()
        /** Held, after taking out [replaced] (unverified copies a validated one replaces) and [evicted] (each with its bound). */
        data class Hold(val replaced: List<GroupArrivalHead>, val evicted: List<Pair<GroupArrivalHead, String>>) : Decision()
    }

    private class Bound(val name: String, val max: Int, val inScope: (GroupArrivalHead) -> Boolean)

    /**
     * What [c] does, given the blobs [held] in arrival order (rowId), whether a
     * group is held pending here ([pendingHere]; a group with blobs that is not
     * pending is not held here), and the distro [distroHex] (a source exempt
     * from the per-source bound). When a bound would be exceeded, the blob
     * evicted is the oldest of the source that holds the most blobs within that
     * bound, only from the class [c] may evict; when nothing may be evicted, [c]
     * is refused.
     */
    fun decide(held: List<GroupArrivalHead>, c: Candidate, pendingHere: (String) -> Boolean, distroHex: String?): Decision {
        if (c.size > BLOB_MAX) return Decision.Refuse("it is ${c.size} bytes, over the ${BLOB_MAX / 1024} KiB a held blob may be")
        val same = held.filter { it.msgHash == c.msgHash }
        if (same.any { it.validated }) return Decision.Duplicate("a copy of it whose signature validated is held")
        if (same.any { it.blobDigest == c.blobDigest }) return Decision.Duplicate("the same blob is held")
        val replaced = if (c.validated) same else emptyList()
        var kept = held.filter { h -> replaced.none { it.rowId == h.rowId } }.sortedBy { it.rowId }
        val evicted = mutableListOf<Pair<GroupArrivalHead, String>>()
        val candidateNotHeld = !pendingHere(c.groupId)
        val mayEvict: (GroupArrivalHead) -> Boolean = { h -> !candidateNotHeld || !pendingHere(h.groupId) }

        val bounds = buildList {
            if (c.srcHex != distroHex && !c.isPostSync) {
                add(Bound("at most $PER_SOURCE per source", PER_SOURCE) { it.srcHex == c.srcHex && !it.isPostSync })
            }
            add(Bound("at most $PER_GROUP per group", PER_GROUP) { it.groupId == c.groupId })
            if (candidateNotHeld) add(Bound("at most $NOT_HELD for groups not held here", NOT_HELD) { !pendingHere(it.groupId) })
            add(Bound("at most $TOTAL in all", TOTAL) { true })
        }
        for (bound in bounds) {
            while (kept.count(bound.inScope) + 1 > bound.max) {
                val victim = victim(kept.filter { bound.inScope(it) && mayEvict(it) })
                    ?: return Decision.Refuse("${bound.name}, and nothing it may evict" + if (candidateNotHeld) " (a group not held here never evicts one held pending)" else "")
                kept = kept.filter { it.rowId != victim.rowId }
                evicted += victim to bound.name
            }
        }
        val byteBound = "at most ${BYTES / 1024} KiB in all"
        while (kept.sumOf { it.size.toLong() } + c.size > BYTES) {
            val victim = victim(kept.filter(mayEvict)) ?: return Decision.Refuse("$byteBound, and nothing it may evict")
            kept = kept.filter { it.rowId != victim.rowId }
            evicted += victim to byteBound
        }
        return Decision.Hold(replaced, evicted)
    }

    /** The oldest blob of the source holding the most of [scope] (ties: the source whose oldest is oldest). */
    private fun victim(scope: List<GroupArrivalHead>): GroupArrivalHead? {
        if (scope.isEmpty()) return null
        val bySource = scope.groupBy { it.srcHex }
        val most = bySource.values.maxOf { it.size }
        return bySource.values.filter { it.size == most }
            .map { blobs -> blobs.minBy { it.rowId } }
            .minBy { it.rowId }
    }
}
