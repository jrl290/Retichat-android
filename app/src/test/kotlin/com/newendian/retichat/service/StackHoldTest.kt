package com.newendian.retichat.service

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The stack's holders (U4): every hold taken is given back exactly once, and
 * the app on screen holds the stack as one holder, not one per activity.
 */
class StackHoldTest {
    /** Stands in for StackRuntime's count; never below zero, as its release logs. */
    private class Holds {
        var count = 0
        var acquires = 0
        var releases = 0
        var unpaired = 0
        fun acquire() { count++; acquires++ }
        fun release() {
            releases++
            if (count == 0) unpaired++ else count--
        }
    }

    /** A lifecycle driven by hand, as ProcessLifecycleOwner's is by the activities. */
    private class Owner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun theAppOnScreenHoldsTheStackOnceAndGivesItBack() {
        val holds = Holds()
        val process = Owner()
        process.registry.addObserver(ForegroundHold(holds::acquire, holds::release))

        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        assertEquals("created, not on screen", 0, holds.count)
        repeat(3) { round ->
            process.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            assertEquals("on screen (round $round): exactly one hold", 1, holds.count)
            process.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            assertEquals("background (round $round): given back", 0, holds.count)
        }
        process.registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        assertEquals(3, holds.acquires)
        assertEquals(3, holds.releases)
        assertEquals("no release without a hold", 0, holds.unpaired)
    }

    /**
     * An activity takes no hold of its own. MainActivity took one in onCreate
     * and one in onStart and gave one back in onStop: each creation, a
     * rotation included, leaked a hold. The Android lifecycle cannot run on
     * the JVM, so the wiring is checked in the source.
     */
    @Test
    fun anActivityTakesNoHoldOfItsOwn() {
        val activity = File("src/main/kotlin/com/newendian/retichat/MainActivity.kt").readText()
        assertFalse(activity.contains("StackRuntime"))
        val app = File("src/main/kotlin/com/newendian/retichat/RetichatApp.kt").readText()
        val hold = app.substringAfter("ProcessLifecycleOwner.get().lifecycle.addObserver(", "")
            .substringBefore("\n        )")
        assertTrue("the hold follows the process, not an activity", hold.contains("ForegroundHold("))
        assertTrue(hold.contains("acquire = { StackRuntime.acquireFromCallback(this) }"))
        assertTrue(hold.contains("release = StackRuntime::release"))
    }

    /**
     * The lifecycle hold is counted before acquireFromCallback returns: a
     * count taken on a launched coroutine could come after ON_STOP's release.
     */
    @Test
    fun theLifecycleHoldIsCountedBeforeItsStartIsLaunched() {
        val runtime = File("src/main/kotlin/com/newendian/retichat/service/StackRuntime.kt").readText()
        val body = runtime.substringAfter("fun acquireFromCallback(context: Context) {")
            .substringBefore("\n    }")
        val counted = body.indexOf("countAcquire()")
        val launched = body.indexOf(".launch {")
        assertTrue(counted >= 0 && launched > counted)
        assertFalse("counted outside the coroutine", body.substringAfter(".launch {").contains("countAcquire()"))
    }

    @Test
    fun holdingGivesTheHoldBackOnceOnEveryPath() = runBlocking {
        val holds = Holds()
        // Returns.
        holdingStack({ holds.acquire(); true }, holds::release) { ready -> assertTrue(ready) }
        // Start failed.
        holdingStack({ holds.acquire(); false }, holds::release) { ready -> assertFalse(ready) }
        // Throws.
        try {
            holdingStack({ holds.acquire(); true }, holds::release) { error("boom") }
            fail("the block's exception is not swallowed")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(3, holds.acquires)
        assertEquals(3, holds.releases)
        assertEquals(0, holds.count)
        assertEquals(0, holds.unpaired)
    }

    /** Cancelled while the stack is still starting: the hold was counted, so it is owed. */
    @Test
    fun aHoldCancelledDuringTheStartIsGivenBack() = runBlocking {
        val holds = Holds()
        val starting = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            holdingStack(
                acquire = { holds.acquire(); starting.complete(Unit); awaitCancellation() },
                release = holds::release,
            ) { fail("the block never runs") }
        }
        starting.await()
        job.cancelAndJoin()
        assertEquals(1, holds.acquires)
        assertEquals(1, holds.releases)
        assertEquals(0, holds.count)
    }
}
