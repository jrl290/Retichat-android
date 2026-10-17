package com.newendian.retichat.service

import com.newendian.retichat.bridge.LxmfFields
import com.newendian.retichat.data.repository.Reactions
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a send writes for reactions (REACTIONS-DESIGN.md §4, §5.2): a Group
 * post's id as key 11 of the Retichat field, and the Distro Sync of a send M
 * with M's LXMF hash as key 12 and, when M is a reaction, M's own 0x40. The
 * reading side of both keys and of 0x40 is ReactionFieldTest's.
 */
class ReactionSendFieldsTest {
    private val recipient = "a".repeat(32)
    private val device = "b".repeat(32)
    private val sentHash = "c".repeat(64)

    /** Records the native calls a [GroupFields.Sink] would make. */
    private class Recorder : GroupFields.Sink {
        val calls = mutableListOf<String>()
        override fun addFieldString(key: Int, value: String) = calls.add("field ${key.toString(16)} $value")
        override fun addFieldBool(key: Int, value: Boolean) = calls.add("field ${key.toString(16)} $value")
        override fun setRetichatString(key: Int, value: String) = calls.add("retichat $key $value")
        override fun setRetichatBool(key: Int, value: Boolean) = calls.add("retichat $key $value")
        override fun setRetichatStatuses(elements: List<ByteArray>) = calls.add("retichat 10 ${elements.size}")
    }

    private val before = listOf(
        "field fb ${LxmfFields.DISTRO_SENT_TYPE}",
        "field fc $recipient",
        "field fd $device",
    )

    @Test
    fun theDistroSyncOfATextSendCarriesItsHashAsKeyTwelve() {
        val sink = Recorder()
        var reactions = 0
        assertTrue(GroupFields.setSentCopy(sink, recipient, device, sentHash, null) { _, _ -> reactions++; true })
        assertEquals(before + "retichat 12 $sentHash", sink.calls)
        assertEquals("no 0x40 on a copy of a text message", 0, reactions)
        assertEquals(12, LxmfFields.RF_SENT_HASH)
    }

    @Test
    fun theDistroSyncOfAReactionCarriesItsHashAndItsOwnReactionField() {
        val sink = Recorder()
        val target = ByteArray(32) { it.toByte() }
        val set = mutableListOf<Pair<ByteArray, String>>()
        assertTrue(GroupFields.setSentCopy(sink, recipient, device, sentHash, Reactions.Outgoing(target, "👍")) { to, c -> set += to to c; true })
        assertEquals(before + "retichat 12 $sentHash", sink.calls)
        assertEquals(1, set.size)
        assertArrayEquals("M's REACTION_TO, unchanged", target, set[0].first)
        assertEquals("👍", set[0].second)
        // A removal is carried as it is: "".
        val removal = mutableListOf<String>()
        assertTrue(GroupFields.setSentCopy(Recorder(), recipient, device, sentHash, Reactions.Outgoing(target, "")) { _, c -> removal += c; true })
        assertEquals(listOf(""), removal)
    }

    @Test
    fun aCopyWithoutAHashIsAsBeforeAndOneThatCannotBeBuiltIsNotMade() {
        val sink = Recorder()
        assertTrue(GroupFields.setSentCopy(sink, recipient, device, null, null) { _, _ -> true })
        assertEquals("no key 12 when M's hash is not known: the copy as before", before, sink.calls)
        // A reaction field that cannot be set: the copy is not made.
        assertFalse(GroupFields.setSentCopy(Recorder(), recipient, device, sentHash, Reactions.Outgoing(ByteArray(32), "👍")) { _, _ -> false })
    }

    @Test
    fun aGroupPostsIdIsKeyEleven() {
        val sink = Recorder()
        val postId = "00112233445566778899aabbccddeeff"
        assertTrue(GroupFields.setPostId(sink, postId))
        assertEquals(listOf("retichat 11 $postId"), sink.calls)
        assertEquals(11, LxmfFields.RF_GROUP_POST_ID)
    }
}
