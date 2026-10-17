package com.newendian.retichat.data.repository

import com.newendian.retichat.data.db.entity.MessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REACTIONS-DESIGN.md §3 `apply_reaction`, §6 the stored form, §7 the chips
 * and which messages take a reaction.
 */
class ReactionsTest {
    private val alice = "a".repeat(32)
    private val bob = "b".repeat(32)
    private val me = "c".repeat(32)
    private val myDistro = "d".repeat(32)

    @Test
    fun aNewerReactionWinsAnEqualOrOlderOneIsIgnored() {
        val first = Reactions.apply(null, alice, "👍", 1_000)!!
        assertEquals("👍", Reactions.of(first, alice))
        // Replaced by a newer one.
        val second = Reactions.apply(first, alice, "❤️", 2_000)!!
        assertEquals("❤️", Reactions.of(second, alice))
        // An older one, arriving late, changes nothing; nor does a repeat (equal time).
        assertNull(Reactions.apply(second, alice, "😂", 1_500))
        assertNull(Reactions.apply(second, alice, "😂", 2_000))
        assertNull(Reactions.apply(second, alice, "❤️", 2_000))
        // Each reactor on their own.
        val both = Reactions.apply(second, bob, "😂", 500)!!
        assertEquals("❤️", Reactions.of(both, alice))
        assertEquals("😂", Reactions.of(both, bob))
    }

    @Test
    fun aRemovalIsKeptSoALateOlderOneCannotBringItBack() {
        val given = Reactions.apply(null, alice, "👍", 1_000)!!
        val removed = Reactions.apply(given, alice, "", 3_000)!!
        assertEquals("", Reactions.of(removed, alice))
        assertTrue("kept as \"e\": \"\"", removed.contains("\"e\":\"\""))
        assertNull("the older 👍 arriving again", Reactions.apply(removed, alice, "👍", 1_000))
        assertNull("one older than the removal", Reactions.apply(removed, alice, "🙏", 2_999))
        assertEquals("🙏", Reactions.of(Reactions.apply(removed, alice, "🙏", 3_001), alice))
        assertTrue("a removal shows no chip", Reactions.chips(removed, emptySet()).isEmpty())
    }

    @Test
    fun theStoredFormRoundTripsAndReadsTolerantly() {
        val json = Reactions.encode(mapOf(bob to Reactions.Entry("say \"hi\"\\ \u0001", 7), alice to Reactions.Entry("👍", 1_790_000_000_123)))
        assertEquals("{\"$alice\":{\"e\":\"👍\",\"t\":1790000000123},\"$bob\":{\"e\":\"say \\\"hi\\\"\\\\ \\u0001\",\"t\":7}}", json)
        assertEquals(mapOf(alice to Reactions.Entry("👍", 1_790_000_000_123), bob to Reactions.Entry("say \"hi\"\\ \u0001", 7)), Reactions.decode(json))
        // Another writer's spacing, escapes, unknown keys and an uppercase reactor.
        val other = """ { "${alice.uppercase()}" : { "e" : "\ud83d\udc4d" , "t" : 5 , "x" : [1, true, null] } , "$bob": "junk" } """
        assertEquals(mapOf(alice to Reactions.Entry("👍", 5)), Reactions.decode(other))
        // Unreadable: none, never a throw.
        for (bad in listOf("", "{", "[]", "{\"a\":{\"e\":\"x\"}}", "{\"a\":{\"e\":\"x\",\"t\":1}} trailing", "nonsense")) {
            assertTrue(bad, Reactions.decode(bad).isEmpty())
        }
        assertTrue(Reactions.decode(null).isEmpty())
    }

    @Test
    fun chipsGroupByEmojiWithCountsAndShowMine() {
        var json: String? = null
        json = Reactions.apply(json, alice, "👍", 3)
        json = Reactions.apply(json, bob, "❤️", 1)
        json = Reactions.apply(json, me, "👍", 2)
        json = Reactions.apply(json, myDistro, "", 4)
        assertEquals(
            listOf(Reactions.Chip("❤️", 1, mine = false), Reactions.Chip("👍", 2, mine = true)),
            Reactions.chips(json, setOf(me, myDistro)),
        )
        // Mine under any address the user sends the chat as.
        assertEquals(
            listOf(Reactions.Chip("❤️", 1, mine = false), Reactions.Chip("👍", 2, mine = false)),
            Reactions.chips(json, setOf(myDistro)),
        )
    }

    @Test
    fun pickingTheSameOneAgainRemovesIt() {
        assertEquals("👍", Reactions.toggled("", "👍"))
        assertEquals("❤️", Reactions.toggled("👍", "❤️"))
        assertEquals("", Reactions.toggled("👍", "👍"))
        assertEquals(6, Reactions.QUICK.size)
        assertTrue(Reactions.QUICK.all { it.toByteArray().size <= 64 })
    }

    @Test
    fun whichMessagesTakeAReaction() {
        val hash = "ef".repeat(32)
        fun msg(id: String, lxmfHash: String? = null, postId: String? = null, systemKind: String? = null) =
            MessageEntity(id, "c", alice, "hi", 1, false, lxmfHash = lxmfHash, postId = postId, systemKind = systemKind)
        // A direct chat: by the id when it is the hash, or by the hash kept beside a local id.
        assertEquals(hash, Reactions.targetHash(msg(hash)))
        assertEquals(hash, Reactions.targetHash(msg("out_1", lxmfHash = hash)))
        assertNull("an outgoing message whose send has no hash yet", Reactions.targetHash(msg("out_1")))
        assertTrue(Reactions.canReact(false, msg(hash)))
        assertFalse(Reactions.canReact(false, msg("out_1")))
        assertFalse("a system line", Reactions.canReact(false, msg(hash, systemKind = "member")))
        // A Group: only a post with a post id.
        assertTrue(Reactions.canReact(true, msg("grp_1", postId = "0f".repeat(16))))
        assertFalse("a post from an older client", Reactions.canReact(true, msg(hash)))
        assertFalse("a system line", Reactions.canReact(true, msg("joined_1", postId = "0f".repeat(16), systemKind = "member")))
    }

    @Test
    fun aPostIdIsSixteenRandomBytesInLowercaseHex() {
        val ids = (1..50).map { Reactions.newPostId() }
        assertTrue(ids.all { Regex("^[0-9a-f]{32}$").matches(it) })
        assertEquals(ids.size, ids.toSet().size)
    }
}
