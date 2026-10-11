package com.newendian.retichat.data.repository

import com.newendian.retichat.data.repository.DistroChanges.Arrivals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a change of the distro held forgets (GROUPS-DISTRO-DESIGN.md §6.5,
 * §8.7, §9.1): the held arrivals and the known siblings go only when D is
 * given up or replaced. At start-up (D held, not loaded yet) nothing changes:
 * the repository is made before the stack loads the stored key, and its
 * first look at the distro used to read "none held", which forgot every held
 * arrival and the siblings of every distro at each cold start (review of
 * 8d330c4).
 */
class DistroChangeTest {
    private val d = "fae321c442e3c9bdcd7a3e79d850e03c"
    private val d2 = "d2".repeat(16)
    private val d3 = "d3".repeat(16)

    @Test
    fun atStartUpBeforeTheStoredKeyIsLookedAtNothingIsForgotten() {
        val plan = DistroChanges.plan(DistroHolding.Unknown, siblingSets = listOf(d, d2), ownMembers = emptySet())
        assertEquals(Arrivals.Keep, plan.arrivals)
        assertTrue(plan.forgetSiblingsOf.isEmpty())
    }

    @Test
    fun aStoredKeyTheCoreCouldNotLoadIsDHeldNotLoadedAndNothingIsForgotten() {
        val plan = DistroChanges.plan(DistroHolding.StoredNotLoaded, siblingSets = listOf(d), ownMembers = emptySet())
        assertEquals(Arrivals.Keep, plan.arrivals)
        assertTrue(plan.forgetSiblingsOf.isEmpty())
    }

    @Test
    fun givingUpDForgetsEveryHeldArrivalAndTheSiblingsOfEveryDistroNoGroupHasAsItsOwnAddress() {
        val plan = DistroChanges.plan(DistroHolding.None, siblingSets = listOf(d, d2), ownMembers = setOf(d2))
        assertEquals(Arrivals.All, plan.arrivals)
        assertEquals(listOf(d), plan.forgetSiblingsOf)
    }

    @Test
    fun loadingDForgetsWhatCameThroughAnotherDistroAndNeverDsOwnSiblings() {
        val plan = DistroChanges.plan(DistroHolding.Loaded(d), siblingSets = listOf(d, d2, d3), ownMembers = setOf(d3))
        assertEquals(Arrivals.AllBut(d), plan.arrivals)
        assertEquals(listOf(d2), plan.forgetSiblingsOf)
    }

    @Test
    fun aColdStartSeesUnknownThenDLoadedAndForgetsNothingOfD() {
        // The order the repository's collector sees at a cold start with D stored.
        val seen = listOf(DistroHolding.Unknown, DistroHolding.Loaded(d))
        val plans = seen.map { DistroChanges.plan(it, siblingSets = listOf(d), ownMembers = emptySet()) }
        assertEquals(listOf(Arrivals.Keep, Arrivals.AllBut(d)), plans.map { it.arrivals })
        assertTrue(plans.all { it.forgetSiblingsOf.isEmpty() })
    }

    // The wiring (what the repository collects, and what the distro says it holds) is pinned by
    // ColdStartTest, which references nothing b8bb597 added, so it runs on a tree without it too.
}
