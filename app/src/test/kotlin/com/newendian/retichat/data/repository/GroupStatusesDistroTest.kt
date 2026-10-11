package com.newendian.retichat.data.repository

import com.newendian.retichat.MemberStatus
import com.newendian.retichat.service.GroupChatManager.Action
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The group follow-up when the user is a distro D (GROUPS-DISTRO-DESIGN.md
 * §9.2, §12): the creator test is "creator is self", so every device of D that
 * holds the group joined answers a keyed accept (§12.1); a status element about
 * any self address is skipped; a self address is never allowlisted and its key
 * never remembered. Messages are the vectors' identities, signed for real.
 */
class GroupStatusesDistroTest {
    private val v = GroupStatusVectors.load()
    private var clock = 1_790_200_000.0
    private fun id(name: String) = v.identities.getValue(name)
    private fun b64(key: ByteArray) = java.util.Base64.getEncoder().encodeToString(key)
    private val deviceA = "0123456789abcdef0123456789abcdef"
    private val deviceB = "fedcba9876543210fedcba9876543210"

    private fun accept(from: String, to: String = v.creator, action: String = Action.ACCEPT): ByteArray {
        val entry = "${id(from).hashHex}:${b64(id(from).publicKey)}"
        val fields = TestLxmf.groupFields(true, v.groupId, action, id(from).hashHex, entry, v.displayNames.getValue(from))
        return TestLxmf.pack(id(from), id(from).hash, id(to).hash, fields, clock.also { clock += 1.0 })
    }

    /** [from]'s accept or leave carrying no key: unverifiable here unless [from]'s key is held. */
    private fun keyless(from: String, to: String, action: String = Action.ACCEPT): ByteArray {
        val fields = TestLxmf.groupFields(true, v.groupId, action, id(from).hashHex, null, v.displayNames.getValue(from))
        return TestLxmf.pack(id(from), id(from).hash, id(to).hash, fields, clock.also { clock += 1.0 })
    }

    private fun status(to: String, elements: List<ByteArray>): ByteArray {
        val fields = TestLxmf.groupFields(true, v.groupId, Action.STATUS, id(v.creator).hashHex, null, v.displayNames.getValue(v.creator), elements)
        return TestLxmf.pack(id(v.creator), id(v.creator).hash, id(to).hash, fields, clock.also { clock += 1.0 })
    }

    @Test
    fun withTheCreatorADistroEveryDeviceOfItThatHoldsTheGroupJoinedAnswersAKeyedAccept() = runBlocking {
        // The vectors' creator stands for D; two devices of D, neither listed.
        val a = GroupRig(v, v.creator, device = deviceA, siblings = setOf(deviceB)).also { it.hold(accepted = true) }
        val b = GroupRig(v, v.creator, device = deviceB, siblings = setOf(deviceA)).also { it.hold(accepted = true) }
        val alice = accept("alice")
        assertEquals(true, a.receive(alice))
        assertEquals(true, b.receive(alice))
        // Mutation: the creator test "creator is the device" answers nothing on either.
        assertEquals(listOf("alice"), a.answeredNames())
        assertEquals(listOf("alice"), b.answeredNames())
        // Each keeps its copy and answers the next keyed accept with it.
        assertEquals(true, a.receive(accept("dave")))
        assertEquals(1, a.answers[1].statuses.size)
    }

    @Test
    fun aDeviceThatAppliedDsLeaveHoldsNoGroupAndAnswersNothing() = runBlocking {
        val a = GroupRig(v, v.creator, device = deviceA).also { it.hold(accepted = true) }
        // A leave G closes the group and removes the conversation (§11.2): the device holds nothing.
        a.store.chats.clear()
        a.store.memberRows.clear()
        assertEquals(null, a.receive(accept("alice")))
        assertTrue(a.answers.isEmpty())
    }

    @Test
    fun aSiblingListedNextToDIsSelfItsAcceptMovesItsOwnRowOnlyNeverAllowlistsItAndItsKeyIsNeverKept() = runBlocking {
        // Frank stands for D, alice for a known sibling of D listed as its own device entry.
        val sibling = id("alice").hashHex
        val r = GroupRig(v, "frank", device = deviceA, siblings = setOf(sibling)).also { it.hold(accepted = true) }
        assertEquals(true, r.receive(accept("alice", to = "frank")))
        assertEquals(MemberStatus.ACCEPTED, r.status("alice"))
        assertEquals(MemberStatus.ACCEPTED, r.status("frank"))
        assertFalse("a self address is never allowlisted", sibling in r.store.allowed)
        assertFalse("never remember one's own key", sibling in r.store.keys)
        assertFalse(sibling in r.native.remembered)
        // Another member's accept is allowlisted and its key kept, as before.
        assertEquals(true, r.receive(accept("bob", to = "frank")))
        assertTrue(id("bob").hashHex in r.store.allowed)
        assertTrue(id("bob").hashHex in r.store.keys)
        // Alignment decision 3: the sibling's line is "Your other device joined the group", a member's its name's.
        assertEquals(listOf(true to true), r.store.lines.values.filter { it.member == sibling }.map { it.joined to it.otherDevice })
        assertEquals(listOf(true to false), r.store.lines.values.filter { it.member == id("bob").hashHex }.map { it.joined to it.otherDevice })
    }

    @Test
    fun aSiblingsOwnLeaveReadsYourOtherDeviceLeftAndAMembersLeaveItsName() = runBlocking {
        val sibling = id("alice").hashHex
        val r = GroupRig(v, "frank", device = deviceA, siblings = setOf(sibling)).also { it.hold(accepted = true) }
        assertEquals(true, r.receive(accept("alice", to = "frank", action = Action.LEAVE)))
        assertEquals(true, r.receive(accept("bob", to = "frank", action = Action.LEAVE)))
        assertEquals(MemberStatus.LEFT, r.status("alice"))
        assertEquals(listOf(false to true), r.store.lines.values.filter { it.member == sibling }.map { it.joined to it.otherDevice })
        assertEquals(listOf(false to false), r.store.lines.values.filter { it.member == id("bob").hashHex }.map { it.joined to it.otherDevice })
        // In a device group (the user is no distro) nobody is another device of the user.
        val plain = GroupRig(v, "frank").also { it.hold(accepted = true) }
        assertEquals(true, plain.receive(accept("alice", to = "frank")))
        assertFalse(plain.store.lines.values.single().otherDevice)
    }

    @Test
    fun aSiblingsAcceptOrLeaveWhoseSignatureIsNotValidatedIsDroppedAndAMembersStillCounts() = runBlocking {
        // RFed SPEC §17.14 (alignment decision 1): a device of D is never another member; only a
        // validly signed message from it is the user's own. A member's is counted until the switch, as before.
        val sibling = id("alice").hashHex
        val r = GroupRig(v, "frank", device = deviceA, siblings = setOf(sibling)).also { it.hold(accepted = true) }
        for (action in listOf(Action.ACCEPT, Action.LEAVE)) {
            assertEquals(null, r.receive(keyless("alice", to = "frank", action = action)))
        }
        assertEquals(MemberStatus.INVITED, r.status("alice"))
        assertTrue(r.store.lines.values.none { it.member == sibling })
        assertEquals(2, r.dropped.count { it.contains("not validated") })
        assertEquals(true, r.receive(keyless("bob", to = "frank")))
        assertEquals(MemberStatus.ACCEPTED, r.status("bob"))
    }

    @Test
    fun aCreatorThatIsSelfNeverAnswersAnotherSelfAddressesKeyedAcceptButStillAnswersAMembers() = runBlocking {
        // Frank stands for D, the group's own address and its recorded creator; alice
        // for a known sibling of D listed as its device entry. §9.2: a self address is
        // never a send target, a status included; §12 answers members only.
        val sibling = id("alice").hashHex
        val r = GroupRig(v, "frank", device = deviceA, siblings = setOf(sibling)).also { it.hold(creator = "frank", accepted = true) }
        assertEquals(true, r.receive(accept("alice", to = "frank")))
        assertTrue("no status to the sibling ${sibling.take(8)}: ${r.answers.map { it.to }}", r.answers.none { it.to == sibling })
        assertTrue(r.logs.any { it.contains("no status to the self address") })
        // A member's keyed accept is still answered, and the sibling's copy is not in it.
        assertEquals(true, r.receive(accept("bob", to = "frank")))
        assertEquals(listOf(id("bob").hashHex), r.answers.map { it.to })
        assertTrue(r.answers.single().statuses.isEmpty())
    }

    @Test
    fun aStatusElementAboutAnySelfAddressIsSkippedAsTheUsersOwn() = runBlocking {
        val sibling = id("alice").hashHex
        // The creator's key is held, as every member's is once the user accepted: its status is proven.
        val r = GroupRig(v, "frank", holds = listOf(v.creator), device = deviceA, siblings = setOf(sibling)).also { it.hold(accepted = true) }
        val elements = listOf(accept("alice"), accept("bob"))
        r.receive(status("frank", elements))
        val tally = r.lastTally!!
        assertEquals(1, tally.counted)
        assertEquals(1, tally.skipped)
        assertTrue(r.logs.any { it.contains("about the user") })
        assertEquals("the sibling's row is not moved by a status", MemberStatus.INVITED, r.status("alice"))
        assertEquals(MemberStatus.ACCEPTED, r.status("bob"))
    }
}
