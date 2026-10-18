package com.newendian.retichat.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GroupChatManager's propagated fallback needs the native library to run, so
 * its wiring is checked in the source (as ConnectionStateManagerTest does for
 * its own); the decisions are GroupPropagationFallbacksTest's.
 */
class GroupFallbackContractTest {
    private val source = File("src/main/kotlin/com/newendian/retichat/service/GroupChatManager.kt").readText()

    private fun body(name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val end = Regex("\n    (/\\*\\*|//|private |internal |fun |val |var )").find(source, start + 1)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    @Test
    fun everyReportAndEverySendGoesThroughTheFallbacks() {
        assertTrue(body("onMessageState").contains("val actions = fallbacks.onState(hash.toHex(), state) ?: return false"))
        assertTrue("reports from before the send was followed are replayed",
            body("track").contains("act(fallbacks.track(hash.toHex(), handle))"))
        val act = body("act")
        assertTrue("the copy is cloned off the router's thread",
            act.contains("scope.launch(Dispatchers.IO) { startPropagationFallback(handle) }"))
        assertTrue(act.contains("actions.release?.let { handle -> RetichatBridge.messageDestroy(handle) }"))
    }

    @Test
    fun aSendThatEndsBeforeItsCloneIsReleasedOnlyAfterTheClone() {
        // 0x10 and FAILED can come in one router pass: releasing the DIRECT
        // handle on FAILED before the fallback cloned it lost the copy.
        val fallback = body("startPropagationFallback")
        val clone = fallback.indexOf("sendPropagatedClone(directHandle)")
        val release = fallback.indexOf("if (fallbacks.cloned(directHandle)) RetichatBridge.messageDestroy(directHandle)")
        assertTrue("released only after the clone was made", clone >= 0 && release > clone)
        assertTrue(fallback.contains("} finally {"))
        assertTrue(body("sendPropagatedClone").contains("RetichatBridge.messageClonePropagated(directHandle)"))
    }
}
