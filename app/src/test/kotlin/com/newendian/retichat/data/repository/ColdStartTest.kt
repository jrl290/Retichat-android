package com.newendian.retichat.data.repository

import com.newendian.retichat.service.DistroManager
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A cold start forgets no held arrival and no known sibling (b8bb597;
 * GROUPS-DISTRO-DESIGN.md §6.5, §8.7, §9.1). The repository is made lazily,
 * by the first screen or the stack, and when a screen comes first it is made
 * before the stack has looked at the stored distro key. Its collector used to
 * read DistroManager.state, whose value there is null, and took that null for
 * "no distro held": it dropped every held arrival and the siblings of every
 * distro no group has as its own address, at each cold start.
 *
 * Nothing here names a type b8bb597 added, so the class compiles and runs on a
 * tree with that commit reverted, where each test fails. [DistroChangeTest]
 * runs the plan the repository acts on.
 */
class ColdStartTest {
    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()

    /** The DistroManager flow the repository's collector hands to onDistroChanged, by name, and the flow itself. */
    private fun collected(): Pair<String, StateFlow<*>> {
        val repo = src("data/repository/ChatRepository.kt")
        val name = Regex("""DistroManager\.(\w+)[^\n]*\.collect \{ onDistroChanged\(it\) \}""").find(repo)?.groupValues?.get(1)
        assertNotNull("ChatRepository collects a DistroManager flow into onDistroChanged", name)
        val getter = DistroManager::class.java.getMethod("get" + name!!.replaceFirstChar { it.uppercase() })
        return name to getter.invoke(DistroManager) as StateFlow<*>
    }

    @Test
    fun atAColdStartTheRepositoryIsNeverToldThatNoDistroIsHeld() {
        // This JVM never runs DistroManager.init: it is the start-up state, before the stack
        // has looked at the stored key, in which a screen can make the repository.
        val (name, flow) = collected()
        val first = flow.value
        assertNotNull(
            "the repository collects DistroManager.$name, whose start-up value is null; read as \"no distro held\", " +
                "it forgets every held arrival and the known siblings of every distro at each cold start",
            first,
        )
        assertEquals("DistroManager.$name at start-up", "not looked at yet (start-up)", first.toString())
    }

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
