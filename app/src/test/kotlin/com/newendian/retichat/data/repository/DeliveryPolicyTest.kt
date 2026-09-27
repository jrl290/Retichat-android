package com.newendian.retichat.data.repository

import com.newendian.retichat.service.GroupChatManager
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

    /** iOS `groupMessagePolicy`: an invite by its source, the rest by the group. */
    @Test
    fun groupMessagesFollowTheIosPolicy() {
        val invite = GroupChatManager.Action.INVITE
        assertTrue(DeliveryPolicy.groupMessage(invite, inviterAllowed = true, groupExists = false))
        assertFalse(DeliveryPolicy.groupMessage(invite, inviterAllowed = false, groupExists = true))
        for (action in listOf(null, GroupChatManager.Action.ACCEPT, GroupChatManager.Action.LEAVE,
            GroupChatManager.Action.RELAY_REQUEST, GroupChatManager.Action.RELAY_DONE)) {
            assertTrue(DeliveryPolicy.groupMessage(action, inviterAllowed = false, groupExists = true))
            assertFalse(DeliveryPolicy.groupMessage(action, inviterAllowed = true, groupExists = false))
        }
    }

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

    @Test
    fun theContactsIosAllowlistsAreAllowlistedHereToo() {
        assertTrue(body(repo, "getOrCreateDirectChat").contains("addContact(destHash)"))
        assertTrue(body(repo, "createGroupChat").contains("ensureAllowlistedContact("))
        assertTrue(body(repo, "acceptGroupInvite").contains("ensureAllowlistedContact("))
        val group = body(repo, "handleGroupMessage")
        // Invite member keys that checked out, the inviter, and an accepting member.
        assertEquals(3, Regex("ensureAllowlistedContact\\(").findAll(group).count())
    }
}
