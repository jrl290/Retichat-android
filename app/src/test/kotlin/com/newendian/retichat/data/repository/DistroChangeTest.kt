package com.newendian.retichat.data.repository

import com.newendian.retichat.data.repository.DistroChanges.Arrivals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    // ---- The wiring: what the collector reads, and what the distro says it holds ----

    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()

    @Test
    fun theRepositoryActsOnWhatTheDeviceHoldsNeverOnTheLoadedStateAlone() {
        val repo = src("data/repository/ChatRepository.kt")
        assertTrue(repo.contains("DistroManager.holding.collect { onDistroChanged(it) }"))
        assertFalse("a null loaded state is start-up as often as a give-up", repo.contains("DistroManager.state.map { it?.deliveryHashHex }"))
        assertTrue(repo.contains("DistroChanges.plan("))
        assertFalse(repo.contains("if (heldDistro == null) groupStatusDao.dropAllArrivals()"))
    }

    @Test
    fun theDistroSaysNoneOnlyWhenNoKeyIsStoredAndStartsUnknown() {
        val dm = src("service/DistroManager.kt")
        assertTrue(dm.contains("MutableStateFlow<DistroHolding>(DistroHolding.Unknown)"))
        val init = dm.substringAfter("fun init(context: Context)").substringBefore("fun generate(")
        assertTrue("no key file: none held", init.contains("_holding.value = DistroHolding.None"))
        assertTrue("a key the core rejects is still held", init.contains("_holding.value = DistroHolding.StoredNotLoaded"))
        val adopt = dm.substringAfter("private fun adopt(").substringBefore("private fun file(")
        assertTrue(adopt.contains("_holding.value = DistroHolding.Loaded("))
        // Given up: said only once the key file is gone.
        val forget = dm.substringAfter("fun forget(context: Context)").substringBefore("fun signing()")
        assertTrue(forget.indexOf("file(context).delete()") in 0 until forget.indexOf("_holding.value ="))
    }
}
