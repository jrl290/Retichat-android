package com.newendian.retichat.ui.newchat

import com.newendian.retichat.data.repository.GroupSelves
import com.newendian.retichat.ui.conversation.DistroGroupTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Creating a group is refused, with nothing written or sent, while the stack is
 * not running or the distro this device holds has not loaded yet
 * (GROUPS-DISTRO-DESIGN.md §8.5, §8.7; alignment decision 3, 2026-10-11), and
 * the New Group screen says why in the words iOS and the web use. Until then
 * Android wrote the group anyway: with the stack down, a group of nobody's
 * whose invites were never sent; with D not loaded, a group as the device that
 * the user's other devices never hear of.
 */
class GroupCreateRefusalTest {
    private val device = "0123456789abcdef0123456789abcdef"
    private val d = "d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0"

    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()
    private val nextMember = Regex("\n    (/\\*\\*|//|private |internal |fun |suspend fun |val |var |@)")

    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val end = nextMember.find(source, start)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    @Test
    fun aCreateIsRefusedWhileTheStackIsNotRunningOrDIsStoredButNotLoaded() {
        assertEquals(GroupSelves.Signer.NOT_READY, GroupSelves.createRefusal("", null, distroStored = false))
        assertEquals("not running comes first", GroupSelves.Signer.NOT_READY, GroupSelves.createRefusal("", null, distroStored = true))
        assertEquals(GroupSelves.Signer.NOT_READY, GroupSelves.createRefusal("", d, distroStored = true))
        assertEquals(GroupSelves.Signer.NOT_LOADED, GroupSelves.createRefusal(device, null, distroStored = true))
        // D loaded: created as D; no distro at all: created as the device.
        assertNull(GroupSelves.createRefusal(device, d, distroStored = true))
        assertNull(GroupSelves.createRefusal(device, null, distroStored = false))
    }

    @Test
    fun theRefusalsAreTheAlignedWordsWordForWord() {
        assertEquals("Your distro address hasn't loaded on this device yet. Create the group once it has.",
            DistroGroupTexts.createRefused(GroupSelves.Signer.NOT_LOADED))
        assertEquals("Retichat isn't running on this device right now. Create the group once it is.",
            DistroGroupTexts.createRefused(GroupSelves.Signer.NOT_READY))
    }

    @Test
    fun theRepositoryRefusesBeforeWritingAnythingAndNeverWritesAGroupItCannotInviteTo() {
        val create = body(src("data/repository/ChatRepository.kt"), "createGroupChat")
        assertTrue(create.contains("): GroupCreation {"))
        val upsert = create.indexOf("chatDao.upsert(chat)")
        assertTrue(upsert >= 0)
        // The refusal from what is held now, before the group id is even made.
        val check = create.indexOf("GroupSelves.createRefusal(if (groupActionsReady.value) selfHex() else \"\", distro?.deliveryHashHex, DistroManager.isStored(appContext))")
        assertTrue(check in 0 until create.indexOf("SecureRandom"))
        // The signer and the manager resolved before the first write: a stack that stopped since refuses too.
        val resolved = create.indexOf("if (signer == null || manager == null) {")
        assertTrue(resolved in check until upsert)
        assertEquals(2, Regex(Regex.escape("return GroupCreation.Refused(")).findAll(create.substring(0, upsert)).count())
        assertFalse("no group is written whose invites are not sent", create.contains("invites not sent"))
        assertTrue(create.contains("return GroupCreation.Created(chatId)"))
    }

    @Test
    fun theNewGroupScreenSaysWhyAndStays() {
        val screen = src("ui/newchat/NewGroupScreen.kt")
        assertTrue(screen.contains("is GroupCreation.Created -> onGroupCreated(created.chatId)"))
        assertTrue(screen.contains("is GroupCreation.Refused -> refusal = DistroGroupTexts.createRefused(created.why)"))
        assertTrue(screen.contains("refusal?.let { why ->"))
        val nav = src("ui/navigation/NavGraph.kt")
        assertTrue(nav.contains("repository.createGroupChat(name, members)"))
    }
}
