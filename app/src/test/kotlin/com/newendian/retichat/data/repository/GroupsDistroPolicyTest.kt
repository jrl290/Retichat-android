package com.newendian.retichat.data.repository

import com.newendian.retichat.MemberStatus
import com.newendian.retichat.bridge.MiniJson
import com.newendian.retichat.data.db.entity.GroupArrivalHead
import com.newendian.retichat.data.repository.DeliveryPolicy.Held
import com.newendian.retichat.data.repository.HeldArrivals.Candidate
import com.newendian.retichat.data.repository.HeldArrivals.Decision
import com.newendian.retichat.names.Signature
import com.newendian.retichat.service.GroupChatManager.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Groups for distro holders, the decisions apart from Room and the JNI
 * (GROUPS-DISTRO-DESIGN.md §6.3, §6.5, §8.1, §9.1): who "me" is in a group,
 * who signs for it, the three first rules of group admission, which dropped
 * messages are held, the held arrivals' bounds, and the invite parser's space.
 */
class GroupsDistroPolicyTest {
    private val device = "0123456789abcdef0123456789abcdef"
    private val d = "fae321c442e3c9bdcd7a3e79d850e03c"
    private val d2 = "d2".repeat(16)
    private val m = "cf0b2a4a8d2a0b6978b71290da7cc80e"
    private val m2 = "e1".repeat(16)
    private val sib = "5".repeat(32)
    private val siblings: (String) -> Set<String> = { if (it == d) setOf(sib) else emptySet() }

    // ---- §8.1 the own address, §9.1 self ----

    @Test
    fun anInviteListingDIsDsAListNamingOnlyTheDeviceIsTheDevicesAndOneNamingNeitherIsNotForTheUser() {
        assertEquals(d, GroupSelves.ownForInvite(listOf(d, m), device, d))
        assertEquals("both listed: the user acts as D only", d, GroupSelves.ownForInvite(listOf(device, d, m), device, d))
        assertEquals(device, GroupSelves.ownForInvite(listOf(device, m), device, d))
        assertEquals(device, GroupSelves.ownForInvite(listOf(device, m), device, null))
        assertNull(GroupSelves.ownForInvite(listOf(m, m2), device, d))
        assertNull("an invite to a D this device does not hold is not for it", GroupSelves.ownForInvite(listOf(d2, m), device, d))
    }

    @Test
    fun inAGroupThatListsXSelfIsXTheDeviceAndXsSiblingsElseTheDeviceAlone() {
        // A D-group.
        val dGroup = GroupSelves.of(d, listOf(d, m, sib), device, d, siblings)
        assertEquals(d, dGroup.own)
        assertEquals(d, dGroup.distro)
        assertEquals(setOf(d, device, sib), dGroup.all)
        // Held at upgrade (no own address recorded) but listing D too: still the device's group, D and siblings are self.
        val upgraded = GroupSelves.of(null, listOf(device, d, m), device, d, siblings)
        assertEquals(device, upgraded.own)
        assertEquals(setOf(d, device, sib), upgraded.all)
        // A D-group whose D this device no longer holds (§8.7): its former D is still self.
        val former = GroupSelves.of(d, listOf(d, m), device, d2, siblings)
        assertEquals(setOf(d, device, sib), former.all)
        // A device-listed group naming no distro this device holds: a sibling listed there is a separate member.
        val plain = GroupSelves.of(null, listOf(device, m, sib), device, d, siblings)
        assertEquals(setOf(device), plain.all)
        assertFalse(plain.isSelf(sib))
        assertEquals(listOf(m, sib), GroupSelves.targets(listOf(device, m, sib), plain))
        // Targets never hold a self address (§8.2).
        assertEquals(listOf(m), GroupSelves.targets(listOf(d, m, sib, device, m), dGroup))
        assertEquals(setOf(device, d, sib), GroupSelves.unheld(device, d, siblings))
        assertEquals(setOf(device), GroupSelves.unheld(device, null, siblings))
    }

    @Test
    fun theSignerIsResolvedFromTheOwnAddressAndWhatIsHeldNowNeverTheDeviceForADGroup() {
        assertEquals(GroupSelves.Signer.DEVICE, GroupSelves.signer(null, device, d, distroStored = true))
        assertEquals(GroupSelves.Signer.DEVICE, GroupSelves.signer(device, device, null, distroStored = false))
        assertEquals(GroupSelves.Signer.DISTRO, GroupSelves.signer(d, device, d, distroStored = true))
        assertEquals("replaced by D'", GroupSelves.Signer.FORMER, GroupSelves.signer(d, device, d2, distroStored = true))
        assertEquals("forgotten", GroupSelves.Signer.FORMER, GroupSelves.signer(d, device, null, distroStored = false))
        assertEquals("stored, not loaded yet", GroupSelves.Signer.NOT_LOADED, GroupSelves.signer(d, device, null, distroStored = true))
        assertTrue(GroupSelves.isDistroGroup(d, device))
        assertFalse(GroupSelves.isDistroGroup(null, device))
        assertFalse(GroupSelves.isDistroGroup(device, device))
    }

    // ---- §6.3 the three first rules ----

    @Test
    fun aForgedMessageAMessageNamingTheUserFromSomeoneElseAndTheUsersOwnEchoAreDropped() {
        val selves = setOf(d, device, sib)
        val echoes = setOf(d)
        // A post to D claiming M2's source with an invalid signature.
        assertTrue(DeliveryPolicy.firstRules(Signature.INVALID, m2, null, selves, echoes)!!.contains("forged"))
        // A post from M naming D (or the device, or a sibling) as GROUP_SENDER.
        for (named in listOf(d, device, sib, " $d\t")) {
            assertTrue(named, DeliveryPolicy.firstRules(Signature.VALIDATED, m, named, selves, echoes)!!.contains("names the user"))
        }
        // From D itself: the user's own echo.
        assertTrue(DeliveryPolicy.firstRules(Signature.VALIDATED, d, d, selves, echoes)!!.contains("echo"))
        // A sibling acting as its own listed entry, naming itself: the user's own act, it goes on.
        assertNull(DeliveryPolicy.firstRules(Signature.VALIDATED, sib, sib, selves, echoes))
        // A member's own post, source unknown (keeps today's rule), and one naming another member.
        assertNull(DeliveryPolicy.firstRules(Signature.SOURCE_UNKNOWN, m, m, selves, echoes))
        assertNull(DeliveryPolicy.firstRules(Signature.VALIDATED, m, m2, selves, echoes))
        // The direct path in a device-listed group: GROUP_SENDER = the device from a member.
        assertTrue(DeliveryPolicy.firstRules(Signature.VALIDATED, m, device, setOf(device), emptySet())!!.contains("names the user"))
    }

    // ---- §6.5 what is held ----

    @Test
    fun onlyAMessageTheGroupNotBeingJoinedIsTheSoleReasonToDropIsHeld() {
        fun holdable(
            action: String? = null, held: Held = Held.PENDING, closed: Boolean = false, filtered: Boolean = false,
            signature: Int = Signature.VALIDATED, sourceIsSelf: Boolean = false, keptIfJoined: Boolean = true,
        ) = DeliveryPolicy.holdable(action, held, closed, filtered, signature, sourceIsSelf, keptIfJoined)
        assertTrue(holdable(Action.STATUS))
        assertTrue(holdable(Action.ACCEPT, signature = Signature.SOURCE_UNKNOWN))
        assertTrue("not held here: proven only", holdable(Action.STATUS, held = Held.NONE))
        assertFalse(holdable(Action.ACCEPT, held = Held.NONE, signature = Signature.SOURCE_UNKNOWN))
        assertFalse("never an invite", holdable(Action.INVITE))
        assertFalse("never a relay request", holdable(Action.RELAY_REQUEST))
        assertFalse("a joined group decides now", holdable(Action.STATUS, held = Held.JOINED))
        assertFalse("closed", holdable(Action.STATUS, closed = true))
        assertFalse("filtered: the filter spends nothing more on it", holdable(Action.STATUS, filtered = true))
        assertFalse("forged", holdable(Action.ACCEPT, signature = Signature.INVALID))
        assertFalse("from a self address", holdable(Action.ACCEPT, sourceIsSelf = true))
        assertFalse("the rule would drop it even joined", holdable(Action.STATUS, keptIfJoined = false))
        // As if joined: a status from the recorded creator, proven.
        val creatorStatus = DeliveryPolicy.groupMessage(Action.STATUS, false, Held.JOINED, MemberStatus.ACCEPTED, false, false, Signature.VALIDATED, sourceIsCreator = true)
        assertTrue(creatorStatus)
        assertFalse(DeliveryPolicy.groupMessage(Action.STATUS, false, Held.PENDING, MemberStatus.ACCEPTED, false, false, Signature.VALIDATED, sourceIsCreator = true))
    }

    // ---- §6.5 the bounds ----

    private var nextRow = 1L
    private fun head(group: String, src: String, size: Int = 100, validated: Boolean = true, post: Boolean = false, hash: String = "h${nextRow}") =
        GroupArrivalHead(nextRow++, group, src, hash, "dig$nextRow", validated, d, post, size)

    private fun cand(group: String, src: String, size: Int = 100, validated: Boolean = true, post: Boolean = false, hash: String = "new") =
        Candidate(group, src, hash, "dignew", validated, post, size)

    @Test
    fun sixtyFourProvenMessagesFromStrangersForRandomGroupsDoNotEvictAHeldCreatorStatus() {
        val pending = "p".repeat(32)
        val status = head(pending, m)
        var held = listOf(status)
        val isPending: (String) -> Boolean = { it == pending }
        var refused = 0
        repeat(64) { i ->
            val stranger = i.toString(16).padStart(32, 's')
            val group = i.toString(16).padStart(32, 'g')
            when (val decision = HeldArrivals.decide(held, cand(group, stranger, hash = "x$i"), isPending, d)) {
                is Decision.Hold -> {
                    assertTrue(decision.evicted.none { it.first.rowId == status.rowId })
                    held = held.filter { h -> decision.evicted.none { it.first.rowId == h.rowId } } + head(group, stranger, hash = "x$i")
                }
                is Decision.Refuse -> refused++
                is Decision.Duplicate -> error("not a duplicate")
            }
        }
        assertEquals(refused, 0)
        assertTrue(status in held)
        assertEquals("at most 16 for groups not held here", 16, held.count { it.groupId != pending })
    }

    @Test
    fun aNotHeldBlobNeverEvictsAPendingGroupsAndIsRefusedWhenOnlyThoseCouldGo() {
        val pending = "p".repeat(32)
        val isPending: (String) -> Boolean = { it == pending }
        // 64 in all, every one for the pending group, from 8 members: nothing a not-held blob may evict.
        val held = (0 until 64).map { head(pending, (it % 8).toString().repeat(32)) }
        val decision = HeldArrivals.decide(held, cand("q".repeat(32), m2), isPending, d)
        // The per-group bound is the pending group's, not the candidate's; its class may evict nothing.
        assertTrue(decision is Decision.Refuse)
        assertTrue((decision as Decision.Refuse).reason.contains("64 in all"))
    }

    @Test
    fun eachBoundEvictsTheOldestOfTheSourceHoldingMostAndEveryBoundIsNamed() {
        val g = "p".repeat(32)
        val pending: (String) -> Boolean = { true }
        // Per source: 8 from M, the ninth evicts M's oldest.
        val fromM = (0 until 8).map { head("g$it".padEnd(32, '0'), m) }
        val bySource = HeldArrivals.decide(fromM, cand(g, m), pending, d) as Decision.Hold
        assertEquals(listOf(fromM.first().rowId), bySource.evicted.map { it.first.rowId })
        assertTrue(bySource.evicted.single().second.contains("per source"))
        // Per group: 16 in one group, 10 from M2 and 6 from M; the next evicts M2's oldest.
        val inGroup = (0 until 10).map { head(g, m2) } + (0 until 6).map { head(g, m) }
        val byGroup = HeldArrivals.decide(inGroup, cand(g, sib), pending, d) as Decision.Hold
        assertEquals(inGroup.first().rowId, byGroup.evicted.single().first.rowId)
        assertTrue(byGroup.evicted.single().second.contains("per group"))
        // Bytes: 256 KiB in all.
        val big = (0 until 4).map { head("b$it".padEnd(32, '0'), "s$it".padEnd(32, '0'), size = 60 * 1024) }
        val byBytes = HeldArrivals.decide(big, cand(g, m, size = 30 * 1024), pending, d) as Decision.Hold
        assertEquals(big.first().rowId, byBytes.evicted.single().first.rowId)
        assertTrue(byBytes.evicted.single().second.contains("KiB"))
        // A single blob over 64 KiB is never held.
        assertTrue(HeldArrivals.decide(emptyList(), cand(g, m, size = 64 * 1024 + 1), pending, d) is Decision.Refuse)
        // A post G (source D) is not bound per source.
        val posts = (0 until 8).map { head("g$it".padEnd(32, '0'), d, post = true) }
        assertTrue((HeldArrivals.decide(posts, cand(g, d, post = true), pending, d) as Decision.Hold).evicted.isEmpty())
    }

    @Test
    fun aLiveAndAPullCopyAreHeldOnceAndAValidatedCopyReplacesUnverifiedOnes() {
        val g = "p".repeat(32)
        val unverified = head(g, m, validated = false, hash = "same")
        val held = listOf(unverified)
        val sameBlob = Candidate(g, m, "same", unverified.blobDigest, false, false, 100)
        assertTrue(HeldArrivals.decide(held, sameBlob, { true }, d) is Decision.Duplicate)
        // Another unverified copy with another signature does not shadow it, nor is it shadowed.
        assertTrue(HeldArrivals.decide(held, Candidate(g, m, "same", "other", false, false, 100), { true }, d) is Decision.Hold)
        val validated = HeldArrivals.decide(held, Candidate(g, m, "same", "v", true, false, 100), { true }, d) as Decision.Hold
        assertEquals(listOf(unverified), validated.replaced)
        val withValid = listOf(head(g, m, validated = true, hash = "same"))
        assertTrue(HeldArrivals.decide(withValid, Candidate(g, m, "same", "x", false, false, 100), { true }, d) is Decision.Duplicate)
    }

    // ---- the invite parser's space ----

    @Test
    fun theInviteParserTrimsExactlyZsAndTabAsTheVectorsSay() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        @Suppress("UNCHECKED_CAST")
        val v = MiniJson.parse(File(root, "rust/retichat-jni/tests/data/distro_group_sync_vectors.json").readText()) as Map<String, Any?>
        val space = (v["invite_space"] as List<*>).map { (it as Long).toInt() }.toSet()
        for (cp in 0..0xFFFF) {
            assertEquals("U+%04X".format(cp), cp in space, DeliveryPolicy.isInviteSpace(cp.toChar()))
        }
        assertEquals(m, DeliveryPolicy.hash("　\t$m "))
        // A newline is not invite space: an entry wrapped in one names nobody, as on iOS and in a group sync.
        assertNull(DeliveryPolicy.hash("\n$m"))
        assertNull(DeliveryPolicy.hash("$m\r"))
        assertEquals(listOf(m, d), DeliveryPolicy.members(" $m ,\t$d,\n$m2"))
        @Suppress("UNCHECKED_CAST")
        val trimmed = (v["variants"] as List<Map<String, Any?>>).first { it["name"] == "accept_members_trimmed_of_zs_and_tab" }
        @Suppress("UNCHECKED_CAST")
        val members = (trimmed["expected"] as Map<String, Any?>)["members"] as String
        assertTrue(d in DeliveryPolicy.members(members))
    }
}
