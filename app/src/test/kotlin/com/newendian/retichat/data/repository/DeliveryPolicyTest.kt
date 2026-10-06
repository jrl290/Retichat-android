package com.newendian.retichat.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DISPLAY_NAMES.md §7: Android keeps exactly what iOS keeps (iOS
 * ChatRepository.swift `allowlistDecision`, `groupMessagePolicy`), enforced in
 * the app with the router's own filter off.
 */
class DeliveryPolicyTest {
    @Test
    fun theFilterKeepsOnlyAllowlistedContacts() {
        assertTrue(DeliveryPolicy.allowlisted(filterStrangers = true, contactExists = true, allowlisted = true))
        assertFalse(DeliveryPolicy.allowlisted(filterStrangers = true, contactExists = true, allowlisted = false))
        assertFalse(DeliveryPolicy.allowlisted(filterStrangers = true, contactExists = false, allowlisted = false))
    }

    @Test
    fun withTheFilterOffEveryoneGetsThrough() {
        assertTrue(DeliveryPolicy.allowlisted(filterStrangers = false, contactExists = false, allowlisted = false))
        assertTrue(DeliveryPolicy.allowlisted(filterStrangers = false, contactExists = true, allowlisted = false))
    }

    // The group rule (James's group model) is pinned in GroupModelTest.

    // ── Wiring (needs the native library to run, so checked in the source) ──

    private val repo = File("src/main/kotlin/com/newendian/retichat/data/repository/ChatRepository.kt").readText()
    private val stackRuntime = File("src/main/kotlin/com/newendian/retichat/service/StackRuntime.kt").readText()
    private val nextMember = Regex("\n    (/\\*\\*|//|private |internal |fun |suspend fun |val |var )")

    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val end = nextMember.find(source, start)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    @Test
    fun theRouterFilterIsTurnedOffNeverPushedFromThePreference() {
        // Until 2026-09-27 the preference (default on) reached the router,
        // whose allowlist nothing filled: every delivery was dropped.
        assertTrue(body(repo, "primeCoreDeliveryPrivacy").contains("routerSetFilterStrangers(routerHandle, false)"))
        assertFalse(body(repo, "setCoreFilterStrangers").contains("routerSetFilterStrangers"))
        // Off before the delivery callback can fire.
        val prime = stackRuntime.indexOf("repo.primeCoreDeliveryPrivacy()")
        val callback = stackRuntime.indexOf("routerSetDeliveryCallback(")
        assertTrue(prime in 0 until callback)
    }

    @Test
    fun theRouterPathAppliesThePolicyBeforeAnyWrite() {
        val received = body(repo, "onMessageReceived")
        val direct = received.indexOf("allowlisted(srcHex)")
        val group = received.indexOf("DeliveryPolicy.groupMessage(")
        val firstWrite = received.indexOf("ensureContact(srcHex)")
        assertTrue(group >= 0 && direct >= 0 && firstWrite >= 0)
        assertTrue(group < firstWrite && direct < firstWrite)
        // The filter preference is read at each delivery, so the toggle applies at once.
        assertTrue(body(repo, "allowlisted").contains("UserPreferences.isFilterStrangersEnabled"))
        // The old contact-exists invite check is gone: the policy covers invites.
        assertFalse(repo.contains("Dropped group invite from stranger"))
    }

    /**
     * RFed-spec Group.md, "The privacy filter" (James, 2026-10-06): the members
     * of a group pass the filter from the user's accept, or from creating the
     * group, not from the invite's arrival (the creator's `status` answer to the
     * accept covers what the filter dropped before). From 2026-10-05 to that
     * change an invite allowlisted its listed members and its inviter as it was
     * processed, as v0.1.8 does, and a member's accept allowlisted it in a
     * pending group too.
     */
    @Test
    fun theUserAllowlistsWhomItChatsWithAndTheMembersOfGroupsItCreatesOrAccepts() {
        assertTrue(body(repo, "getOrCreateDirectChat").contains("addContact(destHash)"))
        assertTrue(body(repo, "createGroupChat").contains("ensureAllowlistedContact("))
        assertTrue(body(repo, "acceptGroupInvite").contains("ensureAllowlistedContact("))
        // An invite allows nobody; a member's own accept is the engine's (GroupStatusEngine.applyChange: only in a joined group).
        assertEquals(0, Regex("ensureAllowlistedContact\\(").findAll(body(repo, "handleGroupMessage")).count())
        assertEquals(0, Regex("ensureAllowlistedContact\\(").findAll(body(repo, "handleGroupInvite")).count())
        assertTrue(body(repo, "handleGroupInvite").contains("ensureContact(memberHash)"))
        // The only other caller: the engine's store, behind the engine's own joined-group check.
        assertTrue(repo.contains("allowContact = { ensureAllowlistedContact(it) }"))
    }
}
