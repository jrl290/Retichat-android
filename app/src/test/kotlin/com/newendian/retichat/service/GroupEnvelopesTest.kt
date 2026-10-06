package com.newendian.retichat.service

import com.newendian.retichat.bridge.GroupEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the accept, leave and status the app sends carry (RFed-spec Group.md,
 * "Member statuses and keys", James 2026-10-06; DISPLAY_NAMES.md §10), in both
 * forms. The bytes of Alice's accept are pinned to the shared vectors in
 * `GroupStatusVectorsTest`; this is the rule. Mirrors the web's
 * `group_statuses.test.mjs` sender tests.
 */
class GroupEnvelopesTest {
    private val self = "0123456789abcdef0123456789abcdef"
    private val group = "5e71ac2d0b1c4f6e9a3d8e7f6c5b4a39"
    private val key = ByteArray(64) { it.toByte() }
    private val entry = "$self:${java.util.Base64.getEncoder().encodeToString(key)}"

    /** Records the native calls a [GroupFields.Sink] would make. */
    private class Recorder : GroupFields.Sink {
        val calls = mutableListOf<String>()
        override fun addFieldString(key: Int, value: String) = calls.add("field ${key.toString(16)} $value")
        override fun addFieldBool(key: Int, value: Boolean) = calls.add("field ${key.toString(16)} $value")
        override fun setRetichatString(key: Int, value: String) = calls.add("retichat $key $value")
        override fun setRetichatBool(key: Int, value: Boolean) = calls.add("retichat $key $value")
        override fun setRetichatStatuses(elements: List<ByteArray>) = calls.add("retichat 10 ${elements.size}")
    }

    private fun written(entries: List<GroupEnvelopes.Entry>, retichat: Boolean) =
        Recorder().also { assertTrue(GroupEnvelopes.write(it, entries, retichat)) }.calls

    @Test
    fun everyAcceptCarriesExactlyTheSendersOwnKeyHashBase64AsAnInviteChunksInTheOldFieldUntilTheSwitchAndKeyNineAfter() {
        assertEquals(entry, GroupEnvelopes.ownKeyEntry(self, key))
        val accept = GroupEnvelopes.accept(group, self, entry)
        assertEquals(listOf(GroupEntry.ID, GroupEntry.ACTION, GroupEntry.SENDER, GroupEntry.MEMBER_KEYS), accept.map { it.entry })
        assertEquals(1, accept.count { it.entry == GroupEntry.MEMBER_KEYS })
        assertEquals(listOf("field a0 $group", "field a3 accept", "field a4 $self", "field a8 $entry"), written(accept, false))
        assertEquals(listOf("retichat 1 $group", "retichat 4 accept", "retichat 5 $self", "retichat 9 $entry"), written(accept, true))
        // The entry is one hash:base64 of a 64-byte key: a hash of 32 hex, a colon, 88 characters of base64.
        assertTrue(Regex("^[0-9a-f]{32}:[A-Za-z0-9+/]{86}==$").matches(entry))
    }

    @Test
    fun aLeaveAndADeclineTheVeryMessageCarryTheSendersOwnKeyTooAndNoContent() {
        // A decline is the leave (James, 2026-10-02): the app has one builder for both.
        val leave = GroupEnvelopes.leave(group, self, entry)
        assertEquals(listOf(GroupEntry.ID, GroupEntry.ACTION, GroupEntry.SENDER, GroupEntry.MEMBER_KEYS), leave.map { it.entry })
        assertEquals(listOf("field a0 $group", "field a3 leave", "field a4 $self", "field a8 $entry"), written(leave, false))
        assertEquals(listOf("retichat 1 $group", "retichat 4 leave", "retichat 5 $self", "retichat 9 $entry"), written(leave, true))
        // An accept and a leave differ in the action alone.
        assertEquals(
            GroupEnvelopes.accept(group, self, entry).map { it.value }.filterIndexed { i, _ -> i != 1 },
            leave.map { it.value }.filterIndexed { i, _ -> i != 1 },
        )
    }

    @Test
    fun anAcceptWhoseOwnKeyCannotBeReadIsStillSentWithoutItAsABuildFromBeforeThisChangeSendsItRatherThanNotAtAll() {
        for (build in listOf(GroupEnvelopes.accept(group, self, null), GroupEnvelopes.leave(group, self, null))) {
            assertEquals(listOf(GroupEntry.ID, GroupEntry.ACTION, GroupEntry.SENDER), build.map { it.entry })
        }
    }

    @Test
    fun aStatusCarriesTheGroupTheActionAndTheCreatorAndNothingElseItsKeyTenIsWrittenSeparately() {
        val status = GroupEnvelopes.status(group, self)
        assertEquals(listOf(GroupEntry.ID, GroupEntry.ACTION, GroupEntry.SENDER), status.map { it.entry })
        assertEquals(listOf(group, "status", self), status.map { it.value })
        assertFalse(status.any { it.entry == GroupEntry.MEMBER_KEYS })
    }

    @Test
    fun beforeTheSwitchTheEntriesAreInTheOldFieldsAndOnlyKeyTenGoesIntoTheRetichatFieldAfterTheSwitchEveryEntryGoesThereAlone() {
        val before = Recorder()
        assertTrue(GroupEnvelopes.write(before, GroupEnvelopes.status(group, self), false))
        assertTrue(GroupFields.setStatuses(before, listOf(ByteArray(120), ByteArray(130))))
        // What a released build reads (the old fields) is where it always was; key 10 is an unknown key inside 0xD1, which it skips.
        assertEquals(listOf("field a0 $group", "field a3 status", "field a4 $self", "retichat 10 2"), before.calls)

        val after = Recorder()
        assertTrue(GroupEnvelopes.write(after, GroupEnvelopes.status(group, self), true))
        assertTrue(GroupFields.setStatuses(after, emptyList()))
        assertEquals(listOf("retichat 1 $group", "retichat 4 status", "retichat 5 $self", "retichat 10 0"), after.calls)
        assertTrue("no old field is written after the switch", after.calls.none { it.startsWith("field") })
    }
}
