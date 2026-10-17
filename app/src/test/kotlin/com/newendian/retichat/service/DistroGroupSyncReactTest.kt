package com.newendian.retichat.service

import com.newendian.retichat.service.DistroGroupSync.Effect
import com.newendian.retichat.service.DistroGroupSync.Local
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REACTIONS-DESIGN.md §5.3: the Distro Sync of Group actions learns the
 * `react` op (`["react", group_id, at_ms, content, left, post_id]`) and a
 * post's `post_id`, in the JSON LXMF-rust's `GroupSync` (600ed00) gives and
 * takes: `post_id` only when the sync has one, so every JSON without one is
 * unchanged; a react requires it.
 */
class DistroGroupSyncReactTest {
    private val group = "9".repeat(32)
    private val postId = "00112233445566778899aabbccddeeff"
    private val d = "d".repeat(32)

    private fun read(vararg entries: Pair<String, Any?>) = DistroGroupSync.read { mapOf(*entries)[it] }

    private fun base(op: String) = arrayOf(
        "by" to "a".repeat(32), "problem" to null, "op" to op, "group_id" to group, "at_ms" to 1_790_000_000_123L,
        "name" to null, "members" to null, "keys" to null, "creator" to null, "left" to emptyList<String>(),
    )

    @Test
    fun aReactIsReadWithItsPostIdAndWrittenAsLxmfRustWritesIt() {
        val read = read(*base("react"), "text" to "👍", "post_id" to postId.uppercase())
        assertNull(read.problem)
        val sync = read.sync!!
        assertEquals(DistroGroupSync.REACT, sync.op)
        assertEquals("👍", sync.text)
        assertEquals(postId, sync.postId)
        // LXMF-rust GroupSync::to_json: post_id after left, the only change.
        assertEquals(
            "{\"op\":\"react\",\"group_id\":\"$group\",\"at_ms\":1790000000123,\"name\":null,\"members\":null,\"keys\":null," +
                "\"creator\":null,\"text\":\"👍\",\"left\":[],\"post_id\":\"$postId\"}",
            sync.toJson(),
        )
        // A removal is "".
        assertEquals("", read(*base("react"), "text" to "", "post_id" to postId).sync!!.text)
    }

    @Test
    fun aReactWithoutAPostIdOrTextIsAProblem() {
        assertEquals("the unwrap's group_sync post_id is not 32 hex", read(*base("react"), "text" to "👍").problem)
        assertEquals("the unwrap's group_sync post_id is not 32 hex", read(*base("react"), "text" to "👍", "post_id" to "xyz").problem)
        assertEquals("the unwrap's group_sync has no text", read(*base("react"), "post_id" to postId).problem)
    }

    @Test
    fun aPostCarriesItsIdWhenItHasOneAndItsJsonIsUnchangedWithout() {
        val with = read(*base("post"), "text" to "hi", "post_id" to postId).sync!!
        assertEquals(postId, with.postId)
        assertTrue(with.toJson().endsWith(",\"left\":[],\"post_id\":\"$postId\"}"))
        // An older device's post: no id, and the JSON every vector pins.
        val without = read(*base("post"), "text" to "hi").sync!!
        assertNull(without.postId)
        assertFalse(without.toJson().contains("post_id"))
        assertTrue(without.toJson().endsWith(",\"text\":\"hi\",\"left\":[]}"))
        // Not an id: ignored, as LXMF-rust ignores it after left.
        assertNull(read(*base("post"), "text" to "hi", "post_id" to "nope").sync!!.postId)
        // The writer's input for a post the user makes now.
        val mine = GroupSync(DistroGroupSync.POST, group, 5, text = "x", postId = postId)
        assertTrue(mine.toJson().endsWith("\"text\":\"x\",\"left\":[],\"post_id\":\"$postId\"}"))
    }

    @Test
    fun aReactIsAppliedInAJoinedGroupHeldLikeAPostOtherwiseAndDroppedWhenClosed() {
        val react = GroupSync(DistroGroupSync.REACT, group, 5, text = "👍", postId = postId)
        val joined = Local.Held(ownIsD = true, joined = true, creator = d, members = setOf(d))
        assertEquals(Effect.ApplyReact, DistroGroupSync.effect(react, joined, d))
        assertEquals(Effect.HoldPost, DistroGroupSync.effect(react, joined.copy(joined = false), d))
        assertEquals(Effect.HoldPost, DistroGroupSync.effect(react, Local.NotHeld, d))
        assertEquals(Effect.DropPost, DistroGroupSync.effect(react, Local.Closed, d))
        assertEquals(Effect.IgnoreOwnAddress, DistroGroupSync.effect(react, joined.copy(ownIsD = false), d))
        assertTrue(DistroGroupSync.REACT in DistroGroupSync.OPS)
    }
}
