package com.newendian.retichat.service

import com.newendian.retichat.bridge.RetichatBridge.MessageState
import com.newendian.retichat.service.GroupPropagationFallbacks.Actions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Group envelope's propagated copy starts as a DM's does: on 0x10 or on the
 * DIRECT send's own failure, whichever comes first, once, and from a report
 * that came before the send was followed. Until 2026-10-18 only a 0x10 after
 * the send was followed started it (L72).
 */
class GroupPropagationFallbacksTest {
    private val nothing = Actions()

    @Test
    fun fallbackRequestStartsTheCopyOnceAndTheDirectSendKeepsItsHandle() {
        val f = GroupPropagationFallbacks()
        assertEquals(nothing, f.track("h1", 7L))
        assertEquals(Actions(copyFrom = 7L), f.onState("h1", MessageState.PROP_FALLBACK_REQUESTED))
        assertEquals("one copy per envelope", nothing, f.onState("h1", MessageState.PROP_FALLBACK_REQUESTED))
        assertFalse("still sending: the caller keeps the handle", f.cloned(7L))
        assertEquals("the DIRECT send ended", Actions(release = 7L), f.onState("h1", MessageState.FAILED))
    }

    @Test
    fun directFailureBeforeAnyFallbackRequestStartsTheCopy() {
        // L72: such an envelope reached no offline member.
        for (state in listOf(MessageState.FAILED, MessageState.REJECTED, MessageState.CANCELLED)) {
            val f = GroupPropagationFallbacks()
            f.track("h1", 7L)
            assertEquals("state $state", Actions(copyFrom = 7L), f.onState("h1", state))
            assertTrue("the handle is released once cloned", f.cloned(7L))
        }
    }

    @Test
    fun aSendThatEndsWhileItsCopyIsClonedLeavesTheHandleForTheClone() {
        // 0x10 and FAILED in one router pass: releasing the handle on FAILED
        // before the clone was made lost the copy.
        val f = GroupPropagationFallbacks()
        f.track("h1", 7L)
        assertEquals(Actions(copyFrom = 7L), f.onState("h1", MessageState.PROP_FALLBACK_REQUESTED))
        assertEquals("not released yet", nothing, f.onState("h1", MessageState.FAILED))
        assertTrue("the clone releases it", f.cloned(7L))
    }

    @Test
    fun aSendThatSucceedsGetsNoCopy() {
        for (state in listOf(MessageState.SENT, MessageState.DELIVERED)) {
            val f = GroupPropagationFallbacks()
            f.track("h1", 7L)
            assertEquals("state $state", Actions(release = 7L), f.onState("h1", state))
            assertNull("then no longer an envelope's", f.onState("h1", MessageState.FAILED))
        }
    }

    @Test
    fun reportsBeforeTheSendIsFollowedAreReplayed() {
        // The router reports 0x10 at once when the link is down, before the
        // send's hash is known here.
        val early = GroupPropagationFallbacks()
        assertNull(early.onState("h1", MessageState.PROP_FALLBACK_REQUESTED))
        assertEquals(Actions(copyFrom = 7L), early.track("h1", 7L))
        assertFalse("still sending", early.cloned(7L))
        assertEquals(Actions(release = 7L), early.onState("h1", MessageState.DELIVERED))

        val failed = GroupPropagationFallbacks()
        assertNull(failed.onState("h1", MessageState.FAILED))
        assertEquals(Actions(copyFrom = 7L), failed.track("h1", 7L))
        assertTrue(failed.cloned(7L))

        val sent = GroupPropagationFallbacks()
        assertNull(sent.onState("h1", MessageState.SENT))
        assertEquals(Actions(release = 7L), sent.track("h1", 7L))
    }

    @Test
    fun theCopysOwnReportsAfterTheDirectSendEndedAreNotAnEnvelopes() {
        // The copy has the DIRECT message's hash.
        val f = GroupPropagationFallbacks()
        f.track("h1", 7L)
        f.onState("h1", MessageState.FAILED)
        f.cloned(7L)
        assertNull(f.onState("h1", MessageState.SENT))
        assertNull(f.onState("h1", MessageState.FAILED))
    }

    @Test
    fun envelopesAreFollowedApart() {
        val f = GroupPropagationFallbacks()
        f.track("h1", 7L)
        f.track("h2", 8L)
        assertNull("another message's report", f.onState("h3", MessageState.FAILED))
        assertEquals(Actions(copyFrom = 8L), f.onState("h2", MessageState.FAILED))
        assertEquals(Actions(copyFrom = 7L), f.onState("h1", MessageState.PROP_FALLBACK_REQUESTED))
    }
}
