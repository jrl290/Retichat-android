package com.newendian.retichat.ui.newchat

import com.newendian.retichat.data.repository.GroupSelves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * New group › Create (James's UI rulings, 2026-10-11; GROUPS-DISTRO-DESIGN.md
 * §8.1, §8.5, §8.7): greyed, with no text, until the start finishes; then
 * created as the distro this device holds, else as the device, a stored distro
 * key that cannot be loaded included (that is a former distro, never "not
 * loaded yet"). Nothing is written or sent for a create refused because the
 * start has not finished. Until 2026-10-11 Create was refused only after the
 * tap, with a text, while the stack was starting or a stored key had not loaded.
 */
class GroupCreateRefusalTest {
    private val device = "0123456789abcdef0123456789abcdef"

    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()
    private val nextMember = Regex("\n    (/\\*\\*|//|private |internal |fun |suspend fun |val |var |@)")

    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val end = nextMember.find(source, start)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    @Test
    fun aCreateIsRefusedOnlyWhileTheStartHasNotFinished() {
        assertEquals(GroupSelves.Signer.NOT_READY, GroupSelves.createRefusal(""))
        // Started: created as the distro held then (D loaded), else as the device, a
        // stored key that cannot be loaded included; nothing about a distro refuses it.
        assertNull(GroupSelves.createRefusal(device))
    }

    @Test
    fun theRepositoryRefusesBeforeWritingAnythingAndNeverWritesAGroupItCannotInviteTo() {
        val create = body(src("data/repository/ChatRepository.kt"), "createGroupChat")
        assertTrue(create.contains("): GroupCreation {"))
        val upsert = create.indexOf("chatDao.upsert(chat)")
        assertTrue(upsert >= 0)
        // The refusal from whether the start has finished, before the group id is even made.
        val check = create.indexOf("GroupSelves.createRefusal(if (groupActionsReady.value) selfHex() else \"\")")
        assertTrue(check in 0 until create.indexOf("SecureRandom"))
        assertFalse("a stored key that is not loaded refuses nothing", create.contains("DistroManager.isStored("))
        // The signer and the manager resolved before the first write: a stack that stopped since refuses too.
        val resolved = create.indexOf("if (signer == null || manager == null) {")
        assertTrue(resolved in check until upsert)
        assertEquals(2, Regex(Regex.escape("return GroupCreation.Refused(")).findAll(create.substring(0, upsert)).count())
        assertFalse("no group is written whose invites are not sent", create.contains("invites not sent"))
        assertTrue(create.contains("return GroupCreation.Created(chatId)"))
    }

    @Test
    fun createIsGreyedUntilTheStartFinishesAndARefusalSaysNothing() {
        val screen = src("ui/newchat/NewGroupScreen.kt")
        assertTrue(screen.contains("enabled = started && groupName.isNotBlank() && selectedMembers.size >= 1 && !isCreating,"))
        assertTrue(screen.contains("is GroupCreation.Created -> onGroupCreated(created.chatId)"))
        // Refused (the start stopped between the screen and the tap): the name and the members stay; no text.
        assertTrue(screen.contains("is GroupCreation.Refused -> Unit"))
        for (gone in listOf("refusal", "DistroGroupTexts", "colorScheme.error")) assertFalse(gone, screen.contains(gone))
        val nav = src("ui/navigation/NavGraph.kt")
        assertTrue(nav.contains("repository.createGroupChat(name, members)"))
        assertTrue(nav.contains("val started by repository.groupActionsReady.collectAsState()"))
        assertTrue(nav.contains("started = started,"))
    }
}
