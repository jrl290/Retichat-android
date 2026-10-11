package com.newendian.retichat.data.repository

import com.newendian.retichat.MemberStatus
import com.newendian.retichat.data.repository.DeliveryPolicy.Held
import com.newendian.retichat.data.repository.DistroGroupAdmission.Facts
import com.newendian.retichat.data.repository.DistroGroupAdmission.Verdict
import com.newendian.retichat.names.Signature
import com.newendian.retichat.service.GroupChatManager.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The admission of a group message that reached this device through its
 * distro D (GROUPS-DISTRO-DESIGN.md §6.3, §6.5, §12.3), run apart from Room:
 * what `ChatRepository.onDistroGroupMessage` does with the verdict is pinned
 * at the end. Each row is a §19.2 row, or a case the review of 8d330c4 found.
 */
class DistroGroupAdmissionTest {
    private val device = "0123456789abcdef0123456789abcdef"
    private val d = "fae321c442e3c9bdcd7a3e79d850e03c"
    private val sib = "5".repeat(32)
    private val creator = "c".repeat(32)
    private val m = "cf0b2a4a8d2a0b6978b71290da7cc80e"
    private val stranger = "e1".repeat(16)

    /** A group held here pending (D's row invited), listing D, the creator and M. */
    private fun facts(
        action: String? = null,
        src: String = m,
        signature: Int = Signature.VALIDATED,
        groupSender: String? = src,
        listed: List<String> = listOf(d, creator, m),
        heldHere: Boolean = true,
        held: Held = if (heldHere) Held.PENDING else Held.NONE,
        sourceStatus: String? = when (src) { creator -> MemberStatus.ACCEPTED; m -> MemberStatus.INVITED; else -> null },
        closed: Boolean = false,
        sourceIsCreator: Boolean = src == creator,
        sourceAllowed: Boolean = false,
        filtered: Boolean = false,
    ) = Facts(
        action = action, signature = signature, srcHex = src, groupSender = groupSender, listed = listed, distro = d,
        heldHere = heldHere, held = held, sourceStatus = sourceStatus, closed = closed, sourceIsCreator = sourceIsCreator,
        selves = if (heldHere) setOf(d, device, sib) else GroupSelves.unheld(device, d) { setOf(sib) },
        echoes = setOf(d), sourceAllowed = sourceAllowed, filtered = { filtered },
    )

    private fun dropped(v: Verdict) = v is Verdict.Drop
    private fun filters(v: Verdict) = (v as? Verdict.Drop)?.filterId == true

    // ---- The invite ----

    @Test
    fun anAllowlistedInviteListingDIsAdmittedForAGroupNotHeldHere() {
        val v = DistroGroupAdmission.decide(facts(Action.INVITE, src = creator, heldHere = false, sourceAllowed = true))
        assertEquals(Verdict.Admit, v)
    }

    @Test
    fun anInviteThroughDThatDoesNotListDIsDroppedWhetherOrNotTheGroupIsHeldHere() {
        for (heldHere in listOf(false, true)) {
            val v = DistroGroupAdmission.decide(facts(Action.INVITE, src = creator, listed = listOf(creator, m, device), heldHere = heldHere, sourceAllowed = true))
            assertTrue("held here: $heldHere, $v", dropped(v))
            assertTrue((v as Verdict.Drop).why.contains("does not list the distro"))
            assertFalse("a list missing D is no filter decision", filters(v))
        }
    }

    @Test
    fun theFilterRecordsTheIdOnlyForAGroupNotHeldHere() {
        // Not held here: the filter decided, and spends nothing more on that group (§6.5).
        val unheld = DistroGroupAdmission.decide(facts(Action.INVITE, src = stranger, heldHere = false))
        assertTrue(dropped(unheld) && filters(unheld))
        // Held here pending (from an allowlisted inviter): the filter admitted it; a non-allowlisted
        // member's invite for it (the §11.2 squat) is dropped and the id stays out of the set.
        val squat = DistroGroupAdmission.decide(facts(Action.INVITE, src = m))
        assertTrue(dropped(squat))
        assertFalse("the pending group's traffic stays holdable", filters(squat))
    }

    @Test
    fun afterASquattersInviteThePendingGroupsNextProvenCreatorStatusIsStillHeld() {
        val filteredSet = mutableSetOf<String>()
        val group = "9".repeat(32)
        fun apply(v: Verdict) { if (filters(v)) filteredSet += group }
        apply(DistroGroupAdmission.decide(facts(Action.INVITE, src = m)))
        val status = DistroGroupAdmission.decide(facts(Action.STATUS, src = creator, filtered = group in filteredSet))
        assertEquals(Verdict.Hold, status)
    }

    // ---- The first rules and the relay request ----

    @Test
    fun theFirstRulesComeFirstOnTheDistroPath() {
        assertTrue("forged", dropped(DistroGroupAdmission.decide(facts(Action.STATUS, src = creator, signature = Signature.INVALID))))
        assertTrue("names the user from someone else", dropped(DistroGroupAdmission.decide(facts(src = m, groupSender = sib))))
        assertTrue("D's own echo", dropped(DistroGroupAdmission.decide(facts(src = d, sourceStatus = MemberStatus.ACCEPTED))))
        // A forged invite never reaches the filter: nothing is recorded.
        val forged = DistroGroupAdmission.decide(facts(Action.INVITE, src = stranger, heldHere = false, signature = Signature.INVALID))
        assertTrue(dropped(forged) && !filters(forged))
    }

    @Test
    fun aRelayRequestThroughDIsNeverActedOnByAnyDeviceOfIt() {
        val v = DistroGroupAdmission.decide(facts(Action.RELAY_REQUEST, src = creator, held = Held.JOINED))
        assertTrue(dropped(v))
        assertTrue((v as Verdict.Drop).why.contains("§12.3"))
    }

    // ---- What is held, what is kept ----

    @Test
    fun aPendingGroupsCreatorStatusIsHeldUnlessTheIdIsFilteredAndAMembersAcceptIsTakenNow() {
        assertEquals(Verdict.Hold, DistroGroupAdmission.decide(facts(Action.STATUS, src = creator)))
        assertEquals(Verdict.Admit, DistroGroupAdmission.decide(facts(Action.ACCEPT, src = m)))
        assertTrue(dropped(DistroGroupAdmission.decide(facts(Action.STATUS, src = creator, filtered = true))))
    }

    @Test
    fun forAGroupNotHeldHereOnlyAProvenMessageIsHeld() {
        assertEquals(Verdict.Hold, DistroGroupAdmission.decide(facts(Action.ACCEPT, src = m, heldHere = false, sourceStatus = null)))
        assertTrue(dropped(DistroGroupAdmission.decide(facts(Action.ACCEPT, src = m, heldHere = false, sourceStatus = null, signature = Signature.SOURCE_UNKNOWN))))
    }

    @Test
    fun aJoinedGroupsProvenPostFromAMemberIsAdmittedAndAStrangersIsKeptAsItsOwn() {
        assertEquals(Verdict.Admit, DistroGroupAdmission.decide(facts(src = m, held = Held.JOINED, sourceStatus = MemberStatus.ACCEPTED)))
        // A plain post is kept for any group held here, whoever sent it (today's rule, §6.3).
        assertEquals(Verdict.Admit, DistroGroupAdmission.decide(facts(src = stranger, held = Held.JOINED)))
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
    fun theDistroPathActsOnTheVerdictAndRecordsTheIdOnlyWhenTheVerdictSaysSo() {
        val b = body("onDistroGroupMessage")
        assertTrue(b.contains("DistroGroupAdmission.decide("))
        assertTrue(b.contains("if (verdict.filterId) UserPreferences.filterInvite(appContext, groupId)"))
        assertEquals("the filtered set is written in one place", 1, Regex("UserPreferences\\.filterInvite\\(").findAll(b).count())
        assertFalse("the list-D rule is the decision's, for every invite", b.contains("here.chat == null && distro !in listed"))
    }

    @Test
    fun anAdmittedInviteThatMakesTheGroupHeldHereTakesItsIdOutOfTheFilteredSet() {
        val b = body("handleGroupInvite")
        val created = b.indexOf("Created PENDING group from invite")
        val unfilter = b.indexOf("UserPreferences.unfilterInvite(appContext, groupId)")
        assertTrue(unfilter in 0 until created)
        assertTrue("after the group is stored", b.indexOf("chatDao.upsert(") in 0 until unfilter)
    }
}
