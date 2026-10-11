package com.newendian.retichat.data.repository

import com.newendian.retichat.MemberStatus
import com.newendian.retichat.bridge.GroupEntry
import com.newendian.retichat.bridge.GroupUnpack
import com.newendian.retichat.bridge.LxmfFields
import com.newendian.retichat.data.model.toHex
import com.newendian.retichat.data.repository.DeliveryPolicy.Held
import com.newendian.retichat.names.Signature
import com.newendian.retichat.service.GroupChatManager.Action
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Member statuses and keys (RFed-spec Group.md, "Member statuses and keys
 * (2026-10-06)"), every rule, through the Kotlin layer: [GroupStatusEngine]
 * with the group rule ([DeliveryPolicy]) in front of it, the native layer behind
 * [FakeGroupNative] (real Ed25519 over real packed messages) and the store in
 * memory. It mirrors the web's `group_statuses.test.mjs`, with the web's test
 * names where the rule is the same; the vectors' own outcomes are in
 * [GroupStatusVectorsTest]. Messages are made here, signed by the vectors'
 * identities: whoever signs a message may be someone else than its source, which
 * is how a forgery is made.
 */
class GroupStatusesTest {
    private val v = GroupStatusVectors.load()
    private var clock = 1_790_100_000.0

    private fun id(name: String) = v.identities.getValue(name)
    private fun at() = clock.also { clock += 1.0 }
    private fun b64(key: ByteArray) = java.util.Base64.getEncoder().encodeToString(key)

    /**
     * An accept or leave from [from] to the creator: carrying [from]'s own key unless [key] is false, in
     * the form [retichat] says, signed by [signer] (default [from]); [keyOf] and [keyEntryHash] make a key
     * that does not bind (whose key, under whose hash); [sender] is GROUP_SENDER.
     */
    private fun msg(
        from: String,
        action: String = Action.ACCEPT,
        key: Boolean = true,
        signer: String = from,
        retichat: Boolean = true,
        to: String = v.creator,
        sender: String = from,
        keyOf: String = from,
        keyEntryHash: String = from,
        groupId: String = v.groupId,
        content: ByteArray = ByteArray(0),
        stamp: Boolean = false,
    ): ByteArray {
        val entry = if (key) "${id(keyEntryHash).hashHex}:${b64(id(keyOf).publicKey)}" else null
        val fields = TestLxmf.groupFields(retichat, groupId, action, id(sender).hashHex, entry, v.displayNames.getValue(from))
        return TestLxmf.pack(id(signer), id(from).hash, id(to).hash, fields, at(), content, stamp)
    }

    /** The creator's `status` to [to] holding [elements] (null: no key 10). */
    private fun status(
        to: String,
        elements: List<ByteArray>?,
        from: String = v.creator,
        signer: String = from,
        retichat: Boolean = true,
        groupId: String = v.groupId,
    ): ByteArray {
        val fields = TestLxmf.groupFields(retichat, groupId, Action.STATUS, id(from).hashHex, null, v.displayNames.getValue(from), elements)
        return TestLxmf.pack(id(signer), id(from).hash, id(to).hash, fields, at())
    }

    private fun rig(me: String = "frank", switched: Boolean = false, holds: List<String> = emptyList()) = GroupRig(v, me, switched, holds)

    private fun creatorRig(switched: Boolean = false, holds: List<String> = emptyList()) =
        GroupRig(v, v.creator, switched, holds).also { it.hold(accepted = true) }

    private val bothForms = listOf(false, true)

    // ── Verdict: what an accept or leave is worth ────────────────────

    @Test
    fun verdictVerifiedCountsForgedIsIgnoredUnverifiableCountsUntilTheSwitchAndIsHeldFromItUncheckedIsNeverCounted() {
        assertEquals(GroupStatuses.Verdict.COUNT, GroupStatuses.verdict(Signature.VALIDATED, false))
        assertEquals(GroupStatuses.Verdict.COUNT, GroupStatuses.verdict(Signature.VALIDATED, true))
        assertEquals(GroupStatuses.Verdict.IGNORE, GroupStatuses.verdict(Signature.INVALID, false))
        assertEquals(GroupStatuses.Verdict.IGNORE, GroupStatuses.verdict(Signature.INVALID, true))
        assertEquals(GroupStatuses.Verdict.COUNT, GroupStatuses.verdict(Signature.SOURCE_UNKNOWN, false))
        assertEquals(GroupStatuses.Verdict.HOLD, GroupStatuses.verdict(Signature.SOURCE_UNKNOWN, true))
        // Anything else (a code the router never reports) is not counted.
        assertEquals(GroupStatuses.Verdict.IGNORE, GroupStatuses.verdict(7, false))
        assertEquals(GroupStatuses.Verdict.IGNORE, GroupStatuses.verdict(-1, true))
    }

    @Test
    fun theLimitsAgreeACreatorKeepsAndPassesOnAPackedMessageOfAtMost96BytesPlusThePayloadLimit() {
        assertEquals(2048, GroupStatuses.STATUS_PAYLOAD_LIMIT)
        assertEquals(96, GroupStatuses.PACKED_HEADER_LENGTH)
        assertEquals(2144, GroupStatuses.STATUS_PACKED_LIMIT)
        assertEquals(128, GroupStatuses.HELD_LIMIT)
        assertEquals(4, GroupStatuses.HELD_PER_MEMBER)
    }

    @Test
    fun anAcceptsOrLeavesKeyEntryIsTheInviteChunksFormHashColonBase64OfThe64BytePublicKeyTheHashItsLxmfDeliveryDestination() {
        for (name in v.identities.keys) {
            val identity = id(name)
            assertEquals(identity.keyEntry, GroupStatuses.keyEntry(identity.hashHex, identity.publicKey))
            assertEquals(identity.hashHex, TestLxmf.deliveryHash(identity.publicKey).toHex())
            assertTrue(Regex("^[0-9a-f]{32}:[A-Za-z0-9+/]{86}==$").matches(identity.keyEntry))
        }
    }

    // ── Receiving an accept or leave ─────────────────────────────────

    @Test
    fun aKeyedAcceptFromASourceNobodyHoldsAKeyForIsVerifiedByTheKeyItCarriesRememberedAndCountsBeforeAndAfterTheSwitchInEitherForm() = runBlocking {
        for (switched in bothForms) for (retichat in bothForms) {
            val r = rig(switched = switched, holds = listOf("creator"))
            r.hold()
            assertEquals(true, r.receive(msg("alice", retichat = retichat)))
            assertEquals(MemberStatus.ACCEPTED, r.status("alice"))
            assertTrue("remembered", id("alice").hashHex in r.native.remembered)
            assertArrayEquals(id("alice").publicKey, r.store.keys[id("alice").hashHex])
            assertEquals(listOf("joined"), r.store.lines.values.map { if (it.joined) "joined" else "left" })
            assertTrue("nothing waits for a key", r.store.heldRows.isEmpty())
        }
    }

    @Test
    fun aKeyedLeaveFromASourceNobodyHoldsAKeyForIsVerifiedRememberedAndFinalALaterAcceptOfTheSameMemberKeyedOrNotIsDroppedUnproved() = runBlocking {
        for (switched in bothForms) {
            val r = rig(switched = switched)
            r.hold()
            assertEquals(true, r.receive(msg("bob", Action.LEAVE)))
            assertEquals(MemberStatus.LEFT, r.status("bob"))
            assertTrue(id("bob").hashHex in r.native.remembered)
            val remembered = r.native.remembered.size
            assertNull(r.receive(msg("bob")))
            assertNull(r.receive(msg("bob", key = false)))
            assertEquals(MemberStatus.LEFT, r.status("bob"))
            assertEquals("dropped by the rule, before any key is remembered", remembered, r.native.remembered.size)
            assertEquals(listOf("accept from bob", "accept from bob"), r.dropped)
        }
    }

    @Test
    fun aForgedAcceptOrLeaveCarryingItsSendersRealKeyIsDroppedUnprovedAndTheMembersOwnMessageStillCounts() = runBlocking {
        for (switched in bothForms) for (action in listOf(Action.ACCEPT, Action.LEAVE)) {
            val r = rig(switched = switched)
            r.hold()
            // Carol's hash and carol's real key, signed by mallory: invalid under the key it carries, which binds.
            assertNull(r.receive(msg("carol", action, signer = "mallory")))
            assertEquals(MemberStatus.INVITED, r.status("carol"))
            assertTrue(r.store.heldRows.isEmpty())
            assertTrue("no key remembered from a dropped message", r.native.remembered.isEmpty() && r.store.keys.isEmpty())
            assertTrue(r.store.lines.isEmpty())
            // Her own message still counts.
            assertEquals(true, r.receive(msg("carol", action)))
            assertEquals(if (action == Action.LEAVE) MemberStatus.LEFT else MemberStatus.ACCEPTED, r.status("carol"))
        }
    }

    @Test
    fun aKeyThatDoesNotBindIsNoKeyCountsBeforeTheSwitchIsHeldFromItAndIsCountedOrDroppedWhenTheRealKeyArrives() = runBlocking {
        // Signed by dave, but the entry under dave's hash holds mallory's key: it derives mallory's destination.
        val genuine = msg("dave", keyOf = "mallory")
        // Mallory under dave's name, carrying her own key as dave's: nothing binds.
        val forged = msg("dave", signer = "mallory", keyOf = "mallory")

        val before = rig(switched = false)
        before.hold()
        assertEquals(true, before.receive(genuine))
        assertEquals(MemberStatus.ACCEPTED, before.status("dave"))
        assertTrue("an entry that does not bind is not remembered", before.native.remembered.isEmpty())

        val from = rig(switched = true)
        from.hold()
        assertEquals(false, from.receive(genuine))
        assertEquals(MemberStatus.INVITED, from.status("dave"))
        assertEquals(1, from.store.heldRows.size)
        // Dave's real key arrives (an announce): the held accept verifies and counts.
        from.native.know(id("dave"))
        from.engine.decideHeld(id("dave").hashHex)
        assertEquals(MemberStatus.ACCEPTED, from.status("dave"))
        assertTrue(from.store.heldRows.isEmpty())

        val forgedFrom = rig(switched = true)
        forgedFrom.hold()
        assertEquals(false, forgedFrom.receive(forged))
        assertEquals(1, forgedFrom.store.heldRows.size)
        forgedFrom.native.know(id("dave"))
        forgedFrom.engine.decideHeld(id("dave").hashHex)
        assertEquals("dropped: it was not dave's", MemberStatus.INVITED, forgedFrom.status("dave"))
        assertTrue(forgedFrom.store.heldRows.isEmpty())
        assertTrue(forgedFrom.store.lines.isEmpty())
    }

    @Test
    fun aKeylessAcceptOrLeaveCountsBeforeTheSwitchAndIsHeldFromItUntilItsSendersKeyArrives() = runBlocking {
        val before = rig(switched = false)
        before.hold()
        assertEquals(true, before.receive(msg("erin", key = false, retichat = false)))
        assertEquals(MemberStatus.ACCEPTED, before.status("erin"))
        assertEquals(true, before.receive(msg("bob", Action.LEAVE, key = false, retichat = false)))
        assertEquals(MemberStatus.LEFT, before.status("bob"))

        val from = rig(switched = true)
        from.hold()
        assertEquals(false, from.receive(msg("erin", key = false, retichat = false)))
        assertEquals(false, from.receive(msg("bob", Action.LEAVE, key = false, retichat = false)))
        assertEquals(MemberStatus.INVITED, from.status("erin"))
        assertEquals("a leave is final only once it is proved", MemberStatus.INVITED, from.status("bob"))
        assertEquals(listOf("erin" to "accept", "bob" to "leave"), from.store.heldRows.map { v.nameOf(it.srcHex) to it.action })
        // A key that arrives decides only the entries held for it.
        from.native.know(id("erin"))
        from.engine.decideHeld(id("erin").hashHex)
        assertEquals(MemberStatus.ACCEPTED, from.status("erin"))
        assertEquals(MemberStatus.INVITED, from.status("bob"))
        assertEquals(listOf("bob"), from.store.heldRows.map { v.nameOf(it.srcHex) })
    }

    @Test
    fun aKeyAlreadyHeldDecidesWhetherOrNotTheMessageCarriesOneAndAKeyedMessageDoesNotReplaceTheHeldKey() = runBlocking {
        val r = rig(holds = listOf("alice"))
        r.hold()
        // Held: verified under it, keyed or keyless.
        assertEquals(true, r.receive(msg("alice", key = false)))
        assertEquals(MemberStatus.ACCEPTED, r.status("alice"))
        // Mallory under alice's name carrying alice's real key: invalid under the held key (and the carried one).
        val r2 = rig(holds = listOf("alice"))
        r2.hold()
        assertNull(r2.receive(msg("alice", signer = "mallory")))
        assertEquals(MemberStatus.INVITED, r2.status("alice"))
        // The held key is never replaced by one carried: the same key, remembered again, changes nothing.
        assertArrayEquals(id("alice").publicKey, r2.native.held[id("alice").hashHex])
    }

    @Test
    fun aKeyedMessageDecidesAMembersOlderHeldAcceptFirstSoTheyCountInTheOrderTheyCameFromTheSwitch() = runBlocking {
        val r = rig(switched = true)
        r.hold()
        assertEquals(false, r.receive(msg("erin", key = false)))
        assertEquals(MemberStatus.INVITED, r.status("erin"))
        // Erin's leave carries her key: her key is here now, so the held accept counts first, then the leave.
        assertEquals(true, r.receive(msg("erin", Action.LEAVE)))
        assertEquals(MemberStatus.LEFT, r.status("erin"))
        assertTrue(r.store.heldRows.isEmpty())
        assertEquals(listOf(true, false), r.store.lines.values.map { it.joined })
    }

    @Test
    fun aMessageItsSourceSignedDecidesWhatWasHeldForThatSourcesKeyBeforeItself() = runBlocking {
        val r = rig(switched = true, holds = listOf("creator"))
        r.hold()
        assertEquals(false, r.receive(msg("erin", key = false)))
        assertEquals(1, r.store.heldRows.size)
        // Her key is held now (an announce brought it, but nobody told the engine); a plain message of hers that verifies
        // reaches the engine as a kept message, and decides her held accept before anything else.
        r.native.know(id("erin"))
        r.engine.onKept(id("erin").hashHex, null, Signature.VALIDATED, null)
        assertEquals(MemberStatus.ACCEPTED, r.status("erin"))
        assertTrue(r.store.heldRows.isEmpty())
    }

    @Test
    fun onlyAListedMembersOwnAcceptOrLeaveCountsAndNoKeyIsRememberedFromAMessageTheRuleDropped() = runBlocking {
        val r = rig(switched = false)
        r.hold()
        // Mallory is on no list, and carries her own key.
        assertNull(r.receive(msg("mallory")))
        // Alice's accept names bob.
        assertNull(r.receive(msg("alice", sender = "bob")))
        // A group this device does not hold.
        assertNull(r.receive(msg("alice", groupId = "00112233445566778899aabbccddeeff")))
        assertEquals(listOf("accept from mallory", "accept from alice", "accept from alice"), r.dropped)
        assertTrue(r.native.remembered.isEmpty() && r.store.keys.isEmpty())
        assertEquals(MemberStatus.INVITED, r.status("alice"))
        assertNull(r.status("mallory"))
        assertTrue(r.store.lines.isEmpty())
    }

    @Test
    fun theCreatorsAcceptIsNotNeededAMembersAcceptInAPendingGroupCountsAndIsRecordedTheUsersAcceptStillAllowsEveryMemberAndNobodyBeforeIt() = runBlocking {
        val r = rig(me = "frank", holds = listOf("creator"))
        r.hold(accepted = false)
        assertEquals(true, r.receive(msg("alice")))
        assertEquals(MemberStatus.ACCEPTED, r.status("alice"))
        assertEquals(MemberStatus.INVITED, r.status("frank"))
        // A pending group lets nobody through the privacy filter: the members pass from the user's accept.
        assertTrue(r.store.allowed.isEmpty())
        assertEquals(Held.PENDING, com.newendian.retichat.GroupMemberStatuses.held(true, r.store.memberRows.getValue(r.chatId), r.self))
        // In a group the user joined, a member's accept lets it through.
        val joined = rig(me = "frank", holds = listOf("creator"))
        joined.hold(accepted = true)
        assertEquals(true, joined.receive(msg("alice")))
        assertEquals(setOf(id("alice").hashHex), joined.store.allowed)
        // A leave lets nobody through.
        assertEquals(true, joined.receive(msg("bob", Action.LEAVE)))
        assertEquals(setOf(id("alice").hashHex), joined.store.allowed)
    }

    @Test
    fun aStampedAcceptIsVerifiedUnderItsCarriedKeyLikeAnyOtherTheStampIsNotSigned() = runBlocking {
        val r = rig(holds = listOf("creator"))
        r.hold()
        assertEquals(true, r.receive(msg("alice", stamp = true)))
        assertEquals(MemberStatus.ACCEPTED, r.status("alice"))
        assertNull("a stamped forgery is forged as well", r.receive(msg("carol", signer = "mallory", stamp = true)))
    }

    // ── The creator keeps the newest and answers with a status ───────

    @Test
    fun theCreatorKeepsEachMembersNewestCountedMessageAsReceivedAndAnswersEachKeyedAcceptWithTheOthersCopiesByMemberHash() = runBlocking {
        val r = creatorRig()
        val alice = msg("alice")
        val erin = msg("erin", key = false, retichat = false)
        val bob = msg("bob", Action.LEAVE)
        assertEquals(true, r.receive(alice))
        assertEquals(true, r.receive(erin))
        assertEquals(true, r.receive(bob))
        // Alice's keyed accept was answered with nobody else's: an empty array is a valid answer.
        // Erin's keyless one and bob's leave are kept for the others, and never answered.
        assertEquals(listOf("alice"), r.answeredNames())
        assertTrue(r.answers[0].statuses.isEmpty())
        assertEquals(setOf("alice", "erin", "bob"), r.store.copyRows.keys.map { v.nameOf(it.second) }.toSet())
        // Dave's keyed accept: every other member's copy, by member hash ascending, none his own, none the creator's.
        assertEquals(true, r.receive(msg("dave")))
        assertEquals(listOf("alice", "dave"), r.answeredNames())
        val sent = r.answers[1]
        assertEquals(id("dave").hashHex, sent.to)
        // Exactly the bytes as received, in member hash order (alice 315f.., erin 6540.., bob 66bb..).
        val byHash = listOf("alice" to alice, "erin" to erin, "bob" to bob).sortedBy { id(it.first).hashHex }
        assertEquals(listOf("alice", "erin", "bob"), byHash.map { it.first })
        assertEquals(byHash.map { it.second.toHex() }, sent.statuses.map { it.toHex() })
    }

    @Test
    fun aStatusAnswersTheAcceptThatJustCountedARepeatedAcceptAnotherMessageIsAnsweredAgainAndIsTheNewestCopy() = runBlocking {
        val r = creatorRig()
        val first = msg("alice")
        val second = msg("alice")
        assertEquals(true, r.receive(first))
        assertEquals(true, r.receive(msg("dave")))
        assertEquals(true, r.receive(second))
        assertEquals(listOf("alice", "dave", "alice"), r.answeredNames())
        assertEquals("the newest accept is the copy", second.toHex(), r.store.copyRows.getValue(r.chatId to id("alice").hashHex).packed.toHex())
        // The answer to the repeated accept carries dave's copy.
        assertEquals(1, r.answers[2].statuses.size)
    }

    @Test
    fun aLeaveIsKeptAsTheCopyAndIsFinalNothingReplacesItALaterAcceptIsDroppedAndNeitherIsAnswered() = runBlocking {
        val r = creatorRig()
        val accept = msg("alice")
        val leave = msg("alice", Action.LEAVE)
        assertEquals(true, r.receive(accept))
        assertEquals(true, r.receive(leave))
        assertEquals(listOf("alice"), r.answeredNames())
        assertEquals(Action.LEAVE, r.store.copyRows.getValue(r.chatId to id("alice").hashHex).action)
        assertEquals(leave.toHex(), r.store.copyRows.getValue(r.chatId to id("alice").hashHex).packed.toHex())
        assertNull(r.receive(msg("alice")))
        assertEquals(MemberStatus.LEFT, r.status("alice"))
        assertEquals(listOf("alice"), r.answeredNames())
        assertEquals(Action.LEAVE, r.store.copyRows.getValue(r.chatId to id("alice").hashHex).action)
    }

    @Test
    fun theCreatorsOwnCopyIsNeverSentInAnAnswerNorTheRecipientsEvenIfOneWereKept() = runBlocking {
        val r = creatorRig()
        val self = r.self
        r.store.putCopy(r.chatId, self, Action.ACCEPT, ByteArray(200) { 1 })
        r.store.putCopy(r.chatId, id("dave").hashHex, Action.ACCEPT, ByteArray(200) { 2 })
        r.store.putCopy(r.chatId, id("bob").hashHex, Action.ACCEPT, ByteArray(200) { 3 })
        // A copy of someone no longer listed is not passed on either.
        r.store.putCopy(r.chatId, id("mallory").hashHex, Action.ACCEPT, ByteArray(200) { 4 })
        assertEquals(true, r.receive(msg("dave")))
        val sent = r.answers.single()
        assertEquals("bob's copy alone: not the creator's, not dave's, not one of a member no longer listed", 1, sent.statuses.size)
        assertEquals(3, sent.statuses[0][0].toInt())
    }

    @Test
    fun onlyAKeyedAcceptIsAnsweredNotAKeylessOneALeaveAForgedOneANonMembersOrAnotherGroupsHeldKeyOrNot() = runBlocking {
        for (holdsKey in bothForms) {
            val r = creatorRig(holds = if (holdsKey) listOf("alice", "bob") else emptyList())
            assertEquals(true, r.receive(msg("alice", key = false)))          // keyless: counts, kept, not answered
            assertEquals(true, r.receive(msg("bob", Action.LEAVE)))           // a leave: never answered
            assertNull(r.receive(msg("carol", signer = "mallory")))           // forged
            assertNull(r.receive(msg("mallory")))                             // no member
            assertNull(r.receive(msg("dave", groupId = "00112233445566778899aabbccddeeff"))) // another group
            assertTrue("holding the key: $holdsKey", r.answers.isEmpty())
            assertEquals(setOf("alice", "bob"), r.store.copyRows.keys.map { v.nameOf(it.second) }.toSet())
        }
    }

    @Test
    fun theOldClientsUnverifiedAcceptIsTheCopyUntilAKeyedOneReplacesItAndAHeldAcceptIsKeptOnceItCounts() = runBlocking {
        val r = creatorRig(switched = false)
        val old = msg("alice", key = false, retichat = false)
        assertEquals(true, r.receive(old))
        assertEquals(old.toHex(), r.store.copyRows.getValue(r.chatId to id("alice").hashHex).packed.toHex())
        assertTrue(r.answers.isEmpty())
        val keyed = msg("alice")
        assertEquals(true, r.receive(keyed))
        assertEquals(keyed.toHex(), r.store.copyRows.getValue(r.chatId to id("alice").hashHex).packed.toHex())
        assertEquals(listOf("alice"), r.answeredNames())

        // From the switch the keyless one is held, kept only once it counts, as the held entry carried it.
        val s = creatorRig(switched = true)
        val held = msg("erin", key = false)
        assertEquals(false, s.receive(held))
        assertTrue(s.store.copyRows.isEmpty())
        s.native.know(id("erin"))
        s.engine.decideHeld(id("erin").hashHex)
        assertEquals(MemberStatus.ACCEPTED, s.status("erin"))
        assertEquals(held.toHex(), s.store.copyRows.getValue(s.chatId to id("erin").hashHex).packed.toHex())
        assertTrue("a held accept is never answered (it carried no key)", s.answers.isEmpty())
    }

    @Test
    fun aCopyIsKeptOnlyForAListedMemberThatHasNotLeftAndNeverOverTheLimitTheAcceptStillCounts() = runBlocking {
        val r = creatorRig()
        // A genuine accept over the limit (a long content): it counts, and no copy is given.
        val big = msg("alice", content = ByteArray(GroupStatuses.STATUS_PAYLOAD_LIMIT + 50) { 'x'.code.toByte() })
        assertTrue(big.size > GroupStatuses.STATUS_PACKED_LIMIT)
        assertEquals(true, r.receive(big))
        assertEquals(MemberStatus.ACCEPTED, r.status("alice"))
        assertTrue(r.store.copyRows.isEmpty())
        // It was answered all the same: it carried its key, and the answer holds no copy of it.
        assertEquals(listOf("alice"), r.answeredNames())
        // One just inside the limit is kept.
        val inside = msg("bob", content = ByteArray(1400) { 'x'.code.toByte() })
        assertTrue(inside.size <= GroupStatuses.STATUS_PACKED_LIMIT)
        assertEquals(true, r.receive(inside))
        assertEquals(1, r.store.copyRows.size)
        // A member that left keeps nothing more: its leave is the copy, nothing newer replaces it.
        assertEquals(true, r.receive(msg("bob", Action.LEAVE)))
        assertNull(r.receive(msg("bob")))
        assertEquals(Action.LEAVE, r.store.copyRows.getValue(r.chatId to id("bob").hashHex).action)
    }

    @Test
    fun onlyTheCreatorAnswersAMemberThatGetsAnAcceptSendsNothingAGroupWhoseCreatorIsNotRecordedHeldBeforeThisChangeNeitherKeepsNorAnswers() = runBlocking {
        // A member (alice) gets bob's accept: counts, keeps nothing, answers nothing.
        val member = rig(me = "alice", holds = listOf("creator"))
        member.hold(accepted = true)
        assertEquals(true, member.receive(msg("bob", to = "alice")))
        assertEquals(MemberStatus.ACCEPTED, member.status("bob"))
        assertTrue(member.answers.isEmpty() && member.store.copyRows.isEmpty())
        // The creator of a group whose creator was never recorded.
        val old = GroupRig(v, v.creator)
        old.hold(accepted = true, creatorRecorded = false)
        assertEquals(true, old.receive(msg("alice")))
        assertEquals(MemberStatus.ACCEPTED, old.status("alice"))
        assertTrue(old.answers.isEmpty() && old.store.copyRows.isEmpty())
    }

    @Test
    fun mixedVersionsAnOldClientSendsNoKeyAndIsSentNoStatusANewOneDoesAndIsTheCreatorTellsItOfTheOldOnesAccept() = runBlocking {
        val r = creatorRig()
        val old = msg("bob", key = false, retichat = false)
        assertEquals(true, r.receive(old))
        assertTrue("the old client is sent nothing: it would not know the action", r.answers.isEmpty())
        assertEquals(true, r.receive(msg("alice")))
        assertEquals(listOf("alice"), r.answeredNames())
        assertEquals(listOf(old.toHex()), r.answers.single().statuses.map { it.toHex() })
    }

    @Test
    fun nothingReplacesALeaveCopyEvenIfTheMemberRowStillSaysInvitedAndACopyOverTheLimitIsNeverKept() = runBlocking {
        val r = creatorRig()
        // A leave is the member's copy for good, whatever the member's row says: a counted accept does not replace it.
        r.store.putCopy(r.chatId, id("alice").hashHex, Action.LEAVE, ByteArray(200) { 9 })
        assertEquals(true, r.receive(msg("alice")))
        val kept = r.store.copyRows.getValue(r.chatId to id("alice").hashHex)
        assertEquals(Action.LEAVE, kept.action)
        assertEquals(9, kept.packed[0].toInt())
        // A packed message over the limit, however it got here, is not a copy.
        val chat = r.store.chats.getValue(r.chatId)
        val counted = r.engine.takeDirect(
            Arrival("m1", id("bob").hashHex, chat, Action.ACCEPT, 1_000, Signature.VALIDATED, null, ByteArray(GroupStatuses.STATUS_PACKED_LIMIT + 1)),
        ) == GroupStatusEngine.Taken.COUNTED
        assertTrue(counted)
        assertEquals(MemberStatus.ACCEPTED, r.status("bob"))
        assertNull(r.store.copyRows[r.chatId to id("bob").hashHex])
        // Exactly at the limit it is kept.
        r.engine.takeDirect(Arrival("m2", id("dave").hashHex, chat, Action.ACCEPT, 1_000, Signature.VALIDATED, null, ByteArray(GroupStatuses.STATUS_PACKED_LIMIT)))
        assertEquals(GroupStatuses.STATUS_PACKED_LIMIT, r.store.copyRows.getValue(r.chatId to id("dave").hashHex).packed.size)
    }

    @Test
    fun theHeldStoreIsBoundedAMessageIsHeldOnceFourForAMemberAndOneHundredTwentyEightInAllAreHeldTheRestDroppedAndThoseHeldStay() = runBlocking {
        val r = rig(switched = true)
        r.hold()
        val chat = r.store.chats.getValue(r.chatId)
        suspend fun offer(src: String, message: String) = r.engine.takeDirect(
            Arrival(message, src, chat, Action.ACCEPT, 1_000, Signature.SOURCE_UNKNOWN, null, ByteArray(300) { 7 }),
        )
        val erin = id("erin").hashHex
        // One message is held once, however often it comes.
        offer(erin, "m1"); offer(erin, "m1")
        assertEquals(1, r.store.heldRows.size)
        // Four for one member, then no more for her: the ones that stay are the first.
        for (n in 2..9) offer(erin, "m$n")
        assertEquals(GroupStatuses.HELD_PER_MEMBER, r.store.heldRows.size)
        assertEquals((1..4).map { "m$it" }, r.store.heldRows.map { it.msgHash })
        assertTrue(r.logs.any { it.contains("4 are held for this member already") })
        // 128 in all: thirty-one more members of four make it, and the next ones are dropped.
        for (member in (1..40).map { TestLxmf.identity("s$it", it).hashHex }) for (n in 1..4) offer(member, "$member-$n")
        assertEquals(GroupStatuses.HELD_LIMIT, r.store.heldRows.size)
        assertTrue(r.logs.any { it.contains("128 are held already") })
        assertEquals("the first stay", listOf("m1", "m2", "m3", "m4"), r.store.heldRows.take(4).map { it.msgHash })
        // A message with nothing to check its signature by (no packed copy) is not held at all.
        val fresh = rig(switched = true)
        fresh.hold()
        fresh.engine.takeDirect(Arrival("m0", erin, fresh.store.chats.getValue(fresh.chatId), Action.ACCEPT, 1_000, Signature.SOURCE_UNKNOWN, null, null))
        assertTrue(fresh.store.heldRows.isEmpty())
        assertTrue(fresh.logs.any { it.contains("what its signature covers was not kept") })
    }

    @Test
    fun whatIsHeldForAGroupThatIsGoneIsReleasedWithoutEffectAndAKeyForSeveralSourcesDecidesThemAllInOrder() = runBlocking {
        val r = rig(switched = true)
        r.hold()
        assertEquals(false, r.receive(msg("erin", key = false)))
        assertEquals(false, r.receive(msg("dave", key = false)))
        assertEquals(false, r.receive(msg("bob", Action.LEAVE, key = false)))
        assertEquals(3, r.store.heldRows.size)
        // An invite or the user's accept: every key may be here now; each held entry is decided, oldest first.
        r.native.know(id("erin")); r.native.know(id("dave")); r.native.know(id("bob"))
        r.engine.decideHeld()
        assertTrue(r.store.heldRows.isEmpty())
        assertEquals(listOf(true, true, false), r.store.lines.values.map { it.joined })
        assertEquals(MemberStatus.LEFT, r.status("bob"))
        // A group deleted meanwhile: its held entries are released and nothing is written.
        val gone = rig(switched = true)
        gone.hold()
        assertEquals(false, gone.receive(msg("erin", key = false)))
        gone.store.chats.clear()
        gone.native.know(id("erin"))
        gone.engine.decideHeld(id("erin").hashHex)
        assertTrue(gone.store.heldRows.isEmpty())
        assertTrue(gone.store.lines.isEmpty())
    }

    // ── Receiving a status ───────────────────────────────────────────

    /** Alice, bob (a leave), erin (keyless) and carol (forged): what a creator might list for frank. */
    private fun listing() = listOf(msg("alice"), msg("bob", Action.LEAVE), msg("erin", key = false))

    @Test
    fun theLateAcceptorTheAcceptsItsClientNeverSawComeBackInTheCreatorsAnswerToItsOwnAcceptAndApplyUnderTheNormalRules() = runBlocking {
        val r = rig(me = "frank", holds = listOf("creator"))
        r.hold(accepted = true)
        // Alice's accept reached frank before the invite, and was dropped: no group was held yet.
        val dropped = GroupRig(v, "frank", holds = listOf("creator"))
        assertNull(dropped.receive(msg("alice")))
        assertEquals(listOf("accept from alice"), dropped.dropped)
        // The creator's answer to frank's own accept brings it back.
        assertNull(r.receive(status("frank", listing())))
        assertEquals(MemberStatus.ACCEPTED, r.status("alice"))
        assertEquals(MemberStatus.LEFT, r.status("bob"))
        assertEquals(MemberStatus.ACCEPTED, r.status("erin"))
        assertEquals(StatusTally(3, 3, 0, 0), r.lastTally)
    }

    @Test
    fun aStatusCountsOnlyFromTheGroupsCreatorWhoseSignatureShowsItEachOtherSourceIsDroppedUnproved() = runBlocking {
        fun fresh() = rig(me = "frank", holds = listOf("creator", "dave")).also { it.hold(accepted = true) }
        // The creator, signed by it.
        fresh().let { r ->
            r.receive(status("frank", listing()))
            assertEquals(MemberStatus.ACCEPTED, r.status("alice"))
        }
        // Another member of a group the user joined (dave), signed by it.
        fresh().let { r ->
            assertNull(r.receive(status("frank", listing(), from = "dave")))
            assertNull(r.lastTally)
            assertEquals(MemberStatus.INVITED, r.status("alice"))
            assertEquals(listOf("status from dave"), r.dropped)
        }
        // A stranger.
        fresh().let { r ->
            assertNull(r.receive(status("frank", listing(), from = "mallory")))
            assertEquals(MemberStatus.INVITED, r.status("alice"))
        }
        // The creator forged: mallory signs it under the creator's name, whose key is held.
        fresh().let { r ->
            assertNull(r.receive(status("frank", listing(), signer = "mallory")))
            assertNull(r.lastTally)
            assertEquals(MemberStatus.INVITED, r.status("alice"))
        }
        // The creator's key not held: its signature shows nothing.
        rig(me = "frank").also { it.hold(accepted = true) }.let { r ->
            assertNull(r.receive(status("frank", listing())))
            assertNull(r.lastTally)
            assertEquals(MemberStatus.INVITED, r.status("alice"))
        }
        // A group whose creator is not recorded (held before this change).
        rig(me = "frank", holds = listOf("creator")).also { it.hold(accepted = true, creatorRecorded = false) }.let { r ->
            assertNull(r.receive(status("frank", listing())))
            assertNull(r.lastTally)
            assertEquals(MemberStatus.INVITED, r.status("alice"))
        }
    }

    @Test
    fun aStatusCountsOnlyInAGroupTheUserHasAcceptedAPendingGroupsAGroupNotHeldAndOneTheUserLeftAreDroppedUnprovedAndNothingIsApplied() = runBlocking {
        val pending = rig(me = "frank", holds = listOf("creator"))
        pending.hold(accepted = false)
        assertNull(pending.receive(status("frank", listing())))
        assertNull(pending.lastTally)
        assertEquals(MemberStatus.INVITED, pending.status("alice"))
        val none = rig(me = "frank", holds = listOf("creator"))
        assertNull(none.receive(status("frank", listing())))
        assertNull(none.lastTally)
        // A group the user left is not held: the same.
        val left = rig(me = "frank", holds = listOf("creator"))
        left.hold(accepted = true)
        left.store.chats.clear()
        assertNull(left.receive(status("frank", listing())))
        assertNull(left.lastTally)
        assertTrue(pending.store.lines.isEmpty() && none.store.lines.isEmpty() && left.store.lines.isEmpty())
    }

    @Test
    fun eachElementIsCheckedOnItsOwnAFailingOneIsSkippedAndTheRestApplyTheCreatorPassesOnOnlyWhatMembersSigned() = runBlocking {
        val r = rig(me = "frank", holds = listOf("creator"))
        r.hold(accepted = true)
        val elements = listOf(
            msg("alice"),                                   // counts
            msg("carol", signer = "mallory"),               // forged: not carol's
            ByteArray(20),                                  // not the size of an accept
            msg("mallory"),                                 // a source that is no member
            msg("dave", sender = "bob"),                    // names another
            msg("bob", Action.LEAVE),                       // counts
        )
        assertNull(r.receive(status("frank", elements)))
        assertEquals(StatusTally(6, 2, 0, 4), r.lastTally)
        assertEquals(MemberStatus.ACCEPTED, r.status("alice"))
        assertEquals(MemberStatus.LEFT, r.status("bob"))
        assertEquals(MemberStatus.INVITED, r.status("carol"))
        assertEquals(MemberStatus.INVITED, r.status("dave"))
        assertNull(r.status("mallory"))
        // An element about this device itself (the user's own accept, handed back) is skipped: it is not news.
        val own = rig(me = "frank", holds = listOf("creator"))
        own.hold(accepted = true)
        own.receive(status("frank", listOf(msg("frank"), msg("frank", Action.LEAVE))))
        assertEquals(StatusTally(2, 0, 0, 2), own.lastTally)
        assertEquals(MemberStatus.ACCEPTED, own.status("frank"))
        assertTrue(own.store.lines.isEmpty() && own.native.remembered.isEmpty())
        // The creator cannot make a member accept: it can only pass on what was signed.
        val forgedByTheCreator = rig(me = "frank", holds = listOf("creator"))
        forgedByTheCreator.hold(accepted = true)
        val unsigned = TestLxmf.pack(id("creator"), id("carol").hash, id("creator").hash,
            TestLxmf.groupFields(true, v.groupId, Action.ACCEPT, id("carol").hashHex, id("carol").keyEntry), at())
        forgedByTheCreator.receive(status("frank", listOf(unsigned)))
        assertEquals("signed by the creator under carol's name: invalid under carol's key", MemberStatus.INVITED, forgedByTheCreator.status("carol"))
    }

    @Test
    fun anElementIsAboutThisStatusesGroupAloneAndOneOverTheSizeLimitIsSkippedWhileTheSameMessageDirectlyIsNot() = runBlocking {
        val otherGroup = "00112233445566778899aabbccddeeff"
        val r = rig(me = "frank", holds = listOf("creator"))
        r.hold(accepted = true)
        r.hold(accepted = true, groupId = otherGroup)
        val aboutOther = msg("alice", groupId = otherGroup)
        r.receive(status("frank", listOf(aboutOther)))
        assertEquals(MemberStatus.INVITED, r.status("alice"))
        assertEquals("another group's", MemberStatus.INVITED, r.status("alice", "group_${otherGroup.take(16)}"))
        assertEquals(StatusTally(1, 0, 0, 1), r.lastTally)
        // Over the size limit as an element; the same message arriving directly counts.
        val big = msg("alice", content = ByteArray(GroupStatuses.STATUS_PAYLOAD_LIMIT + 50) { 'x'.code.toByte() })
        r.receive(status("frank", listOf(big)))
        assertEquals(StatusTally(1, 0, 0, 1), r.lastTally)
        assertEquals(MemberStatus.INVITED, r.status("alice"))
        assertEquals(true, r.receive(big))
        assertEquals(MemberStatus.ACCEPTED, r.status("alice"))
    }

    @Test
    fun anElementAppliesWithTheNormalRulesALeaveStaysFinalAndOrderDoesNotMatterWhetherTheElementsTheDirectMessagesOrBothComeFirst() = runBlocking {
        val accept = msg("alice")
        val leave = msg("alice", Action.LEAVE)
        // Elements first, direct after.
        val a = rig(me = "frank", holds = listOf("creator")); a.hold(accepted = true)
        a.receive(status("frank", listOf(leave)))
        assertNull(a.receive(accept))
        assertEquals(MemberStatus.LEFT, a.status("alice"))
        // Direct first, then an element that is older: nothing comes back from left.
        val b = rig(me = "frank", holds = listOf("creator")); b.hold(accepted = true)
        assertEquals(true, b.receive(leave))
        b.receive(status("frank", listOf(accept)))
        assertEquals(MemberStatus.LEFT, b.status("alice"))
        assertEquals(StatusTally(1, 0, 0, 1), b.lastTally)
        // Both in one status: the leave wins whichever is listed first.
        for (order in listOf(listOf(accept, leave), listOf(leave, accept))) {
            val c = rig(me = "frank", holds = listOf("creator")); c.hold(accepted = true)
            c.receive(status("frank", order))
            assertEquals(MemberStatus.LEFT, c.status("alice"))
        }
    }

    @Test
    fun anUnverifiableElementCountsUntilTheSwitchAndIsHeldFromItAForgedOneUnderAHeldKeyIsSkippedEitherWay() = runBlocking {
        for (switched in bothForms) {
            val r = rig(me = "frank", switched = switched, holds = listOf("creator", "carol"))
            r.hold(accepted = true)
            val keyless = msg("erin", key = false)
            val forgedUnderHeldKey = msg("carol", key = false, signer = "mallory")
            r.receive(status("frank", listOf(keyless, forgedUnderHeldKey)))
            assertEquals(if (switched) StatusTally(2, 0, 1, 1) else StatusTally(2, 1, 0, 1), r.lastTally)
            assertEquals(if (switched) MemberStatus.INVITED else MemberStatus.ACCEPTED, r.status("erin"))
            assertEquals(MemberStatus.INVITED, r.status("carol"))
            assertEquals(if (switched) 1 else 0, r.store.heldRows.size)
        }
    }

    // ── RFed SPEC §17.14 "Repeated keys": what the native layer refuses whole ──

    @Test
    fun anElementTheNativeLayerRefusesIsSkippedAsRefusedNeverCountedOrHeld() = runBlocking {
        for (switched in bothForms) {
            val r = rig(me = "frank", switched = switched, holds = listOf("creator"))
            r.hold(accepted = true)
            val refused = msg("alice")
            val unverifiable = msg("erin", key = false)
            r.native.refused += listOf(refused, unverifiable)
            r.receive(status("frank", listOf(refused, unverifiable, msg("bob", Action.LEAVE))))
            assertEquals(StatusTally(3, 1, 0, 2), r.lastTally)
            assertEquals(MemberStatus.INVITED, r.status("alice"))
            assertEquals(MemberStatus.INVITED, r.status("erin"))
            assertEquals(MemberStatus.LEFT, r.status("bob"))
            assertTrue("nothing refused is held", r.store.heldRows.isEmpty())
            // The skip names the refusal; the native layer wrote the refusal's own line.
            for (index in 0..1) {
                assertTrue(r.logs.toString(), r.logs.any { it.startsWith("Skipped element $index of the status") && it.endsWith(": ${GroupUnpack.Refused}") })
            }
            assertFalse(r.logs.any { it.contains("not an LXMF message") })
        }
    }

    @Test
    fun aHeldEntryTheNativeLayerNowRefusesIsReleasedNeverCounted() = runBlocking {
        val r = rig(me = "frank", switched = true, holds = listOf("creator"))
        r.hold(accepted = true)
        val keyless = msg("erin", key = false)
        r.receive(status("frank", listOf(keyless)))
        assertEquals(1, r.store.heldRows.size)
        // Held before this build refused such a message; erin's key arrives.
        r.native.refused += keyless
        r.native.know(id("erin"))
        r.engine.decideHeld(id("erin").hashHex)
        assertTrue(r.store.heldRows.isEmpty())
        assertEquals(MemberStatus.INVITED, r.status("erin"))
        assertTrue(r.logs.toString(), r.logs.any { it.startsWith("Dropped held accept") && it.endsWith("it no longer unpacks (${GroupUnpack.Refused})") })
    }

    @Test
    fun aStatusReadsAtMostAsManyElementsAsTheGroupHasMembersAnEmptyOneOrOneWithNoKeyTenChangesNothingAndTheSameStatusTwiceIsOne() = runBlocking {
        val r = rig(me = "frank", holds = listOf("creator"))
        r.hold(accepted = true)
        val alice = msg("alice")
        // 20 elements for a group of 7 members: only 7 are read (each counts, the first moves alice).
        r.receive(status("frank", List(20) { alice }))
        assertEquals(StatusTally(20, 7, 0, 13), r.lastTally)
        assertEquals(1, r.store.lines.size)
        // An empty status, and one with no key 10, change nothing.
        r.receive(status("frank", emptyList()))
        assertEquals(StatusTally(0, 0, 0, 0), r.lastTally)
        r.receive(status("frank", null))
        assertEquals(StatusTally(0, 0, 0, 0), r.lastTally)
        // The same status twice is one: the second changes nothing.
        val twice = status("frank", listOf(msg("bob", Action.LEAVE)))
        r.receive(twice)
        val lines = r.store.lines.size
        r.receive(twice)
        assertEquals(lines, r.store.lines.size)
        assertEquals(MemberStatus.LEFT, r.status("bob"))
    }

    @Test
    fun theCreatorsStatusIsReadWholeEvenOnceTheCreatorHasLeftBeingOnlyTheCarrierOfWhatMembersSigned() = runBlocking {
        val r = rig(me = "frank", holds = listOf("creator"))
        r.hold(accepted = true)
        r.store.setMemberStatus(r.chatId, id("creator").hashHex, MemberStatus.LEFT)
        r.receive(status("frank", listOf(msg("alice"))))
        assertEquals(MemberStatus.ACCEPTED, r.status("alice"))
        assertEquals(StatusTally(1, 1, 0, 0), r.lastTally)
    }

    // ── The group rule for a status ──────────────────────────────────

    private fun statusRule(
        held: Held = Held.JOINED,
        sourceIsCreator: Boolean = true,
        signature: Int = Signature.VALIDATED,
        sourceStatus: String? = MemberStatus.ACCEPTED,
    ) = DeliveryPolicy.groupMessage(
        action = Action.STATUS, sourceAllowed = false, held = held, sourceStatus = sourceStatus,
        namesOther = false, closed = false, signature = signature, sourceIsCreator = sourceIsCreator,
    )

    @Test
    fun theGroupRuleForAStatusAsATableOnlyTheCreatorOnlyForAJoinedGroupOnlyASignatureThatShowsIt() {
        assertTrue(statusRule())
        assertFalse(statusRule(held = Held.PENDING))
        assertFalse(statusRule(held = Held.NONE))
        assertFalse(statusRule(sourceIsCreator = false))
        assertFalse(statusRule(signature = Signature.SOURCE_UNKNOWN))
        assertFalse(statusRule(signature = Signature.INVALID))
        // Nothing else about the source matters: a creator that left, or is on no list, still carries it.
        assertTrue(statusRule(sourceStatus = MemberStatus.LEFT))
        assertTrue(statusRule(sourceStatus = null))
        // The default of a source that is not the creator: a group whose creator is not recorded.
        assertFalse(DeliveryPolicy.groupMessage(Action.STATUS, false, Held.JOINED, MemberStatus.ACCEPTED, false, false, Signature.VALIDATED))
    }

    // ── Key 10 in the fields ─────────────────────────────────────────

    @Test
    fun groupStatusesReadsKeyTenOfTheRetichatFieldOnlyAndAnArrayOnlyElementsAsTheyCame() {
        val one = ByteArray(120) { 5 }
        val elements = listOf(Mp.bin(one), Mp.int(7), Mp.str("x"))
        fun map(vararg entries: Pair<ByteArray, ByteArray>) = Mp.map(entries.toList())
        // Key 10 of a Retichat field that is a map: the array, elements as they came (a bin is a ByteArray, others are not).
        val inRetichat = LxmfFields.decode(map(Mp.int(0xD1) to map(Mp.int(10) to Mp.array(elements))))
        val read = inRetichat.groupStatuses()!!
        assertEquals(3, read.size)
        assertArrayEquals(one, read[0] as ByteArray)
        assertEquals(7L, read[1])
        assertEquals("x", read[2])
        // An empty array is an empty list.
        assertEquals(emptyList<Any?>(), LxmfFields.decode(map(Mp.int(0xD1) to map(Mp.int(10) to Mp.array(emptyList())))).groupStatuses())
        // Not from a top-level field (key 10 is not a field number, and 0x0A is LXMF's FIELD_RESULTS).
        assertNull(LxmfFields.decode(map(Mp.int(10) to Mp.array(elements))).groupStatuses())
        // Not from a Retichat field that is not a map, not when it is not an array, not when absent.
        assertNull(LxmfFields.decode(map(Mp.int(0xD1) to Mp.array(elements))).groupStatuses())
        assertNull(LxmfFields.decode(map(Mp.int(0xD1) to map(Mp.int(10) to Mp.str("nope")))).groupStatuses())
        assertNull(LxmfFields.decode(map(Mp.int(0xD1) to map(Mp.int(1) to Mp.str("g")))).groupStatuses())
        assertNull(LxmfFields.decode(ByteArray(0)).groupStatuses())
        // The entries before them read as before: key 10 is not one of the nine.
        assertEquals(9, GroupEntry.entries.size)
        assertEquals(10, LxmfFields.RF_GROUP_STATUSES)
        assertTrue(GroupEntry.entries.none { it.key == LxmfFields.RF_GROUP_STATUSES })
    }
}
