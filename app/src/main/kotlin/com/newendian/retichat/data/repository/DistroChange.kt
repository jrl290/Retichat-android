package com.newendian.retichat.data.repository

/**
 * What this device holds of a distro D, as a change of it is seen
 * (GROUPS-DISTRO-DESIGN.md §6.5, §8.7, §9.1).
 */
sealed class DistroHolding {
    /** Not looked at yet: the app is up and the stack has not loaded the stored key (start-up). */
    object Unknown : DistroHolding() { override fun toString() = "not looked at yet (start-up)" }
    /** A key is stored but the core could not load it: D is held, not loaded. */
    object StoredNotLoaded : DistroHolding() { override fun toString() = "stored, not loaded" }
    /** No key is stored: no distro is held (never, or given up). */
    object None : DistroHolding() { override fun toString() = "none" }
    /** D is loaded: [delivery] is its `lxmf.delivery` hash. */
    data class Loaded(val delivery: String) : DistroHolding() { override fun toString() = "loaded ${delivery.take(8)}" }
}

/** What a change of the distro held forgets. */
object DistroChanges {
    /** What the held-arrival store forgets (§6.5). */
    sealed class Arrivals {
        object Keep : Arrivals()
        object All : Arrivals()
        data class AllBut(val distro: String) : Arrivals()
    }

    /** [arrivals], and the distros whose known siblings are dropped (§9.1). */
    data class Plan(val arrivals: Arrivals, val forgetSiblingsOf: List<String>)

    /**
     * What to forget when the device's [holding] is seen, with [siblingSets] the
     * distros a set of known siblings is kept for and [ownMembers] those of them
     * a group here still has as its own address.
     *
     * - Start-up ([DistroHolding.Unknown]) or a stored key not loaded: D is
     *   held, not loaded; nothing changes (§8.7, DESIGN_PRINCIPLES §5).
     * - None held (given up, or never): every held arrival came through a
     *   distro no longer held (§6.5), and the siblings of every distro no group
     *   here has as its own address go (§9.1).
     * - D loaded (at start, generated or imported): what came through another
     *   distro goes, and the siblings of every other distro no group here has
     *   as its own address.
     */
    fun plan(holding: DistroHolding, siblingSets: List<String>, ownMembers: Set<String>): Plan = when (holding) {
        DistroHolding.Unknown, DistroHolding.StoredNotLoaded -> Plan(Arrivals.Keep, emptyList())
        DistroHolding.None -> Plan(Arrivals.All, siblingSets.filter { it !in ownMembers })
        is DistroHolding.Loaded -> Plan(
            Arrivals.AllBut(holding.delivery),
            siblingSets.filter { it != holding.delivery && it !in ownMembers },
        )
    }
}
