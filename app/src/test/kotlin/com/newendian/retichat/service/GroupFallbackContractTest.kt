package com.newendian.retichat.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GroupChatManager's propagated fallback needs the native library to run, so
 * its ordering rule is checked in the source (as ConnectionStateManagerTest
 * does for its own).
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
    fun aSendThatEndsBeforeItsCloneLeavesTheHandleForTheFallback() {
        // 0x10 and FAILED can come in one router pass: releasing the DIRECT
        // handle on FAILED before the fallback cloned it lost the copy.
        val state = body("onMessageState")
        assertTrue(state.contains("synchronized(cloneLock) { cloning.add(handle) }"))
        assertTrue(state.contains("if (handle in cloning) { releaseAfterClone.add(handle); false } else true"))
        assertTrue(state.contains("if (releaseNow) RetichatBridge.messageDestroy(handle)"))
        val fallback = body("startPropagationFallback")
        val clone = fallback.indexOf("sendPropagatedClone(directHandle)")
        val release = fallback.indexOf("if (release) RetichatBridge.messageDestroy(directHandle)")
        assertTrue("released only after the clone was made", clone >= 0 && release > clone)
        assertTrue(fallback.contains("} finally {"))
        assertTrue(body("sendPropagatedClone").contains("RetichatBridge.messageClonePropagated(directHandle)"))
    }
}
