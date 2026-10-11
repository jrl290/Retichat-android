package com.newendian.retichat.data.repository

import com.newendian.retichat.data.repository.DeliveryPolicy.GroupPost
import com.newendian.retichat.names.Signature
import com.newendian.retichat.service.GroupChatManager.Action
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * GROUPS-DISTRO-DESIGN.md §6.2: a group message's `grp:` key is recorded only
 * when the message is kept (applied or stored); a dropped one is judged again
 * on its next copy. The group handler says whether it kept the message, and
 * the distro path records the key from that. Until the review of 8d330c4 the
 * distro path returned "kept" for whatever reached the handler, so a sibling's
 * post first seen unverified was recorded as seen and never judged again.
 */
class GroupMessageKeptTest {
    // ---- A plain post, by its packet source (§9.2) ----

    @Test
    fun aSelfSourcesPostIsTheUsersOwnOnlyWhenItsSignatureValidatedAndIsOtherwiseNotKept() {
        assertEquals(GroupPost.OWN, DeliveryPolicy.groupPost(sourceIsSelf = true, signature = Signature.VALIDATED, hasContent = true))
        assertEquals("an own post with no text is still the user's", GroupPost.OWN, DeliveryPolicy.groupPost(true, Signature.VALIDATED, false))
        for (s in listOf(Signature.SOURCE_UNKNOWN, Signature.INVALID)) {
            val p = DeliveryPolicy.groupPost(true, s, true)
            assertEquals(GroupPost.UNPROVEN_OWN, p)
            assertFalse("judged again on its next copy", p.kept)
        }
        assertTrue(GroupPost.OWN.kept)
    }

    @Test
    fun anotherSourcesPostIsKeptOnlyWithTextOrAnAttachment() {
        assertEquals(GroupPost.MEMBER, DeliveryPolicy.groupPost(false, Signature.SOURCE_UNKNOWN, true))
        assertTrue(GroupPost.MEMBER.kept)
        val empty = DeliveryPolicy.groupPost(false, Signature.VALIDATED, false)
        assertEquals(GroupPost.EMPTY, empty)
        assertFalse(empty.kept)
    }

    // ---- An accept or leave: what the engine did with it ----

    @Test
    fun anAcceptTheEngineCountedOrHeldIsKeptAndOneItIgnoredIsNot() = runBlocking {
        val v = GroupStatusVectors.load()
        val r = GroupRig(v, v.creator, switched = true).also { it.hold(accepted = true) }
        val chat = r.store.chats.getValue(r.chatId)
        val alice = v.identities.getValue("alice").hashHex
        fun arrival(id: String, signature: Int) = Arrival(id, alice, chat, Action.ACCEPT, 1_000, signature, null, ByteArray(8))
        val held = r.engine.takeDirect(arrival("m1", Signature.SOURCE_UNKNOWN))
        assertEquals(GroupStatusEngine.Taken.HELD, held)
        assertTrue("held for its key: stored", held.kept)
        val ignored = r.engine.takeDirect(arrival("m2", Signature.INVALID))
        assertEquals(GroupStatusEngine.Taken.IGNORED, ignored)
        assertFalse(ignored.kept)
        assertTrue(GroupStatusEngine.Taken.COUNTED.kept)
    }

    // ---- The wiring ----

    private val repo = File("src/main/kotlin/com/newendian/retichat/data/repository/ChatRepository.kt").readText()
    private fun body(name: String): String {
        val start = repo.indexOf("fun $name(")
        require(start >= 0) { name }
        val open = repo.indexOf('{', repo.indexOf(')', start))
        var depth = 0
        for (i in open until repo.length) {
            when (repo[i]) { '{' -> depth++; '}' -> if (--depth == 0) return repo.substring(start, i + 1) }
        }
        error(name)
    }

    @Test
    fun theDistroPathRecordsTheKeyFromWhatTheHandlerKept() {
        val b = body("onDistroGroupMessage")
        assertTrue(b.contains("val kept = handleGroupMessage(hash, srcHex, content, timestamp, fields, here, signature, keys.senderKey, keys.packed, viaDistro = true)"))
        assertTrue(b.contains("if (kept) DistroGroupOutcome.KEPT else"))
        assertFalse("never kept whatever the handler did", Regex("viaDistro = true\\)\\s*\\n\\s*DistroGroupOutcome\\.KEPT").containsMatchIn(b))
    }

    @Test
    fun theHandlerSaysKeptOnlyForWhatItAppliedOrStored() {
        val h = body("handleGroupMessage")
        assertTrue(h.contains("): Boolean {"))
        assertTrue(h.contains("return handleGroupInvite(srcHex, groupId, timestamp, fields, standing, viaDistro)"))
        assertTrue(h.contains("groupStatuses.takeDirect(") && h.contains(").kept"))
        assertTrue(h.contains("val post = DeliveryPolicy.groupPost(standing.isSelf(srcHex), signature, content.isNotBlank() || attachments.isNotEmpty())"))
        assertTrue(h.contains("post.kept"))
        assertTrue(h.contains("GroupPost.UNPROVEN_OWN -> {") && h.contains("GroupPost.EMPTY -> {"))
        // An unrecognised action is not kept.
        assertTrue(Regex("else -> \\{\\s*Log\\.w\\(TAG, \"handleGroupMessage: unrecognised action=\\\$groupAction[^\"]*\"\\)\\s*false").containsMatchIn(h))
        val invite = body("handleGroupInvite")
        assertTrue(invite.contains("): Boolean {"))
        // Each drop of the invite handler says not kept.
        assertEquals(3, Regex("DROPPED[^\\n]*\\n\\s*return false").findAll(invite).count() + Regex("ignored\"\\)\\s*\\n\\s*return false").findAll(invite).count())
    }
}
