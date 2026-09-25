package com.newendian.retichat.service

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * WakeWorker (U4): one release for its one acquire on every path, the
 * budget running out included; one worker at a time; and no
 * getForegroundInfo needed at the minSdk the app ships.
 */
class WakeWorkerTest {
    private class Counts {
        var acquires = 0
        var releases = 0
    }

    /**
     * The budget cancels the catch-up. The release in the finally runs then;
     * the budget branch released a second time, giving back a hold the app
     * on screen owned. Catch-up here never ends, so the budget is the only
     * way out: the outcome does not depend on the clock.
     */
    @Test
    fun theBudgetRunningOutReleasesOnce() = runBlocking {
        val counts = Counts()
        val inBudget = WakeWorker.wake(
            budgetMs = 20,
            acquire = { counts.acquires++; true },
            release = { counts.releases++ },
        ) { awaitCancellation() }
        assertFalse(inBudget)
        assertEquals(1, counts.acquires)
        assertEquals("acquires == releases", counts.acquires, counts.releases)
    }

    @Test
    fun theBudgetRunningOutDuringTheStartReleasesOnce() = runBlocking {
        val counts = Counts()
        val inBudget = WakeWorker.wake(
            budgetMs = 20,
            acquire = { counts.acquires++; awaitCancellation() },
            release = { counts.releases++ },
        ) { error("catch-up never runs") }
        assertFalse(inBudget)
        assertEquals(1, counts.acquires)
        assertEquals(1, counts.releases)
    }

    @Test
    fun aWakeThatFinishesReleasesOnce() = runBlocking {
        val counts = Counts()
        var caughtUp = false
        val inBudget = WakeWorker.wake(
            budgetMs = 60_000,
            acquire = { counts.acquires++; true },
            release = { counts.releases++ },
        ) { ready -> caughtUp = ready }
        assertTrue(inBudget)
        assertTrue(caughtUp)
        assertEquals(1, counts.acquires)
        assertEquals(1, counts.releases)
    }

    @Test
    fun aFailedStartReleasesOnce() = runBlocking {
        val counts = Counts()
        var told: Boolean? = null
        WakeWorker.wake(
            budgetMs = 60_000,
            acquire = { counts.acquires++; false },
            release = { counts.releases++ },
        ) { ready -> told = ready }
        assertEquals(false, told)
        assertEquals(1, counts.acquires)
        assertEquals(1, counts.releases)
    }

    /** The worker's hold goes through wake(); it releases nothing itself. */
    @Test
    fun theWorkerTakesItsHoldOnlyThroughWake() {
        val worker = File("src/main/kotlin/com/newendian/retichat/service/WakeWorker.kt").readText()
        val doWork = worker.substringAfter("override suspend fun doWork(): Result {")
        assertEquals(1, Regex("StackRuntime\\.acquire\\(").findAll(worker).count())
        assertFalse(worker.contains("StackRuntime.release()"))
        assertTrue(doWork.contains("release = StackRuntime::release,"))
    }

    /**
     * Every `wake <id>: start` in the log has its `end`, also when WorkManager
     * stops the worker or the catch-up throws: the staging proof pairs them
     * to show one worker at a time. doWork needs a WorkManager runtime, so
     * this is checked in the source.
     */
    @Test
    fun everyStartInTheLogHasItsEnd() {
        val worker = File("src/main/kotlin/com/newendian/retichat/service/WakeWorker.kt").readText()
        val doWork = worker.substringAfter("override suspend fun doWork(): Result {")
        val end = "Log.i(TAG, \"wake \$id: end\")"
        assertEquals("one end line", 1, worker.split(end).size - 1)
        val started = doWork.indexOf("Log.i(TAG, \"wake \$id: start\")")
        val tried = doWork.indexOf("try {")
        val woken = doWork.indexOf("wake(")
        val finally = doWork.indexOf("} finally {")
        assertTrue("start, then try, then the wake", started in 0 until tried && tried < woken && woken < finally)
        assertTrue("the end line is in the finally", doWork.substringAfter("} finally {").substringBefore("}").contains(end))
    }

    /**
     * One worker at a time: a push while a wake is pending or running is
     * covered by it. WorkManager needs the Android runtime, so the enqueue
     * is checked in the source.
     */
    @Test
    fun wakesAreUniqueWorkKeptWhileOneIsPending() {
        val worker = File("src/main/kotlin/com/newendian/retichat/service/WakeWorker.kt").readText()
        val enqueue = worker.substringAfter("fun enqueue(context: Context) {").substringBefore("\n        }")
        assertTrue(enqueue.contains(".enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, req)"))
        assertFalse(enqueue.contains(".enqueue(req)"))
        // Every wake goes through enqueue().
        val sources = listOf(File("src/main"), File("src/debug"))
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { it.readText().contains("OneTimeWorkRequestBuilder<WakeWorker>") }
            .map { it.name }.toList()
        assertEquals(listOf("WakeWorker.kt"), sources)
    }

    /**
     * WorkManager 2.9.1 asks an expedited worker for getForegroundInfo below
     * API 31 only (WorkForegroundRunnable.run returns at once when
     * SDK_INT >= 31); CoroutineWorker's default throws "Not implemented".
     * So WakeWorker needs no override only while minSdk is at least 31.
     */
    @Test
    fun noForegroundInfoIsNeededAtTheMinSdk() {
        val gradle = File("build.gradle.kts").readText()
        val minSdk = Regex("\n\\s*minSdk = (\\d+)").find(gradle)?.groupValues?.get(1)?.toInt()
        val worker = File("src/main/kotlin/com/newendian/retichat/service/WakeWorker.kt").readText()
        assertTrue(
            "minSdk $minSdk: an expedited WakeWorker below 31 needs getForegroundInfo",
            (minSdk ?: 0) >= 31 || worker.contains("override suspend fun getForegroundInfo()"),
        )
    }
}
