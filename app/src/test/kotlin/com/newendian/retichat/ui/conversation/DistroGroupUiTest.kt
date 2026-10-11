package com.newendian.retichat.ui.conversation

import com.newendian.retichat.data.repository.GroupActing
import com.newendian.retichat.data.repository.GroupSelves
import com.newendian.retichat.names.NameBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What a group whose own address is the user's distro D shows
 * (GROUPS-DISTRO-DESIGN.md §8.7, §9.2, §13): the "on all your devices" texts
 * for decline, leave and delete; in a group whose D this device no longer
 * holds, the read-only text, no composer, accept, decline or leave, and only
 * "Delete from this device"; every self address shown as "You".
 */
class DistroGroupUiTest {
    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()
    private val screen = src("ui/conversation/ConversationScreen.kt")
    private val sheet = src("ui/conversation/ChatInfoSheet.kt")
    private val model = src("ui/conversation/ConversationViewModel.kt")

    private val device = "0123456789abcdef0123456789abcdef"
    private val d = "d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0"
    private val sibling = "5151515151515151515151515151515a"

    @Test
    fun theTextsAreTheDesignsWordForWord() {
        assertEquals("Decline this invitation? This declines it on all your devices, and you cannot join this group later.",
            DistroGroupTexts.DECLINE_TITLE + " " + DistroGroupTexts.DECLINE_TEXT)
        assertEquals("Leave this group? You will leave it on all your devices, its messages are deleted from all of them, and you cannot rejoin.",
            DistroGroupTexts.LEAVE_TITLE + " " + DistroGroupTexts.LEAVE_TEXT)
        assertEquals("Delete this group? This leaves it and deletes its messages on all your devices, and you cannot rejoin.",
            DistroGroupTexts.DELETE_TITLE + " " + DistroGroupTexts.DELETE_TEXT)
        assertEquals("Delete from this device", DistroGroupTexts.LOCAL_DELETE)
        assertEquals("This removes the group from this device only. You stay in the group on your other devices and for the other members.",
            DistroGroupTexts.LOCAL_DELETE_TEXT)
        assertEquals("This group uses your distro address d0d0d0d0…, which this device no longer holds. " +
            "You can read it here, but cannot post or change your membership from this device.", DistroGroupTexts.formerDistro(d))
    }

    @Test
    fun theGroupViewSaysWhyNothingIsOfferedInTheAlignedWordsForEachSignerThatCannotAct() {
        // Alignment decision 3 (2026-10-11): the same words on iOS, Android and the web.
        assertEquals("Retichat isn't running on this device right now. You can post and change your membership once it is.",
            DistroGroupTexts.unavailable(GroupSelves.Signer.NOT_READY, d))
        assertEquals("Your distro address hasn't loaded on this device yet. You can post and change your membership once it has.",
            DistroGroupTexts.unavailable(GroupSelves.Signer.NOT_LOADED, d))
        assertEquals(DistroGroupTexts.formerDistro(d), DistroGroupTexts.unavailable(GroupSelves.Signer.FORMER, d))
        assertEquals(null, DistroGroupTexts.unavailable(GroupSelves.Signer.DEVICE, device))
        assertEquals(null, DistroGroupTexts.unavailable(GroupSelves.Signer.DISTRO, d))
        // Each signer that cannot act is read only, and only those.
        for (signer in GroupSelves.Signer.values()) {
            val acting = GroupActing(distroGroup = true, signer = signer, own = d, selves = setOf(d, device))
            assertEquals(signer.toString(), acting.readOnly, DistroGroupTexts.unavailable(signer, d) != null)
        }
    }

    @Test
    fun everySelfAddressOfTheGroupReadsYou() {
        val names = NameBook(emptyMap(), device)
        val selves = setOf(device, d, sibling)
        assertEquals("You", names.member(d, selves))
        assertEquals("You", names.member(sibling.uppercase(), selves))
        assertEquals("You", names.member(device, emptySet()))
        // A sibling outside a group that names D is a member like any other (§9.1).
        assertEquals("51515151…", names.member(sibling, setOf(device)))
        assertTrue(sheet.contains("val name = names.member(member.destHashHex, acting?.selves.orEmpty())"))
    }

    @Test
    fun onlyAFormerDistroGroupIsReadOnlyAndOffersTheLocalDelete() {
        fun acting(signer: GroupSelves.Signer) = GroupActing(distroGroup = signer != GroupSelves.Signer.DEVICE, signer = signer, own = d, selves = setOf(d, device))
        assertFalse(acting(GroupSelves.Signer.DEVICE).readOnly)
        assertFalse(acting(GroupSelves.Signer.DISTRO).readOnly)
        assertTrue(acting(GroupSelves.Signer.NOT_LOADED).readOnly)
        assertFalse(acting(GroupSelves.Signer.NOT_LOADED).former)
        assertTrue(acting(GroupSelves.Signer.FORMER).readOnly)
        assertTrue(acting(GroupSelves.Signer.FORMER).former)
        assertTrue(model.contains("chat?.takeIf { it.isGroup }?.let { repository.groupActing(it) }"))
    }

    @Test
    fun theConversationDisablesWhatWouldSendAndSaysWhyInAFormerDistroGroup() {
        assertTrue(screen.contains("val readOnly = acting?.readOnly == true"))
        // The composer: field, attach and send.
        assertTrue(screen.contains("onValueChange = { draft = it },\n                            enabled = !readOnly,"))
        assertTrue(screen.contains("IconButton(onClick = { filePickerLauncher.launch(\"*/*\") }, enabled = !readOnly)"))
        assertTrue(screen.contains("enabled = !readOnly && (draft.isNotBlank() || pendingAttachmentName != null),"))
        // Accept and decline.
        assertTrue(screen.contains("onClick = { showDeclineConfirm = true },\n                                enabled = !readOnly,"))
        assertTrue(screen.contains("onClick = { viewModel?.acceptInvite() },\n                                enabled = !readOnly,"))
        // The text, in the invite bar and above the composer: why, for a former D, D not loaded yet, or the stack not running.
        assertTrue(screen.contains("val unavailable = acting?.let { DistroGroupTexts.unavailable(it.signer, it.own) }"))
        assertEquals(1, Regex(Regex.escape("text = unavailable,")).findAll(screen).count())
        // Above the composer: that, or why the last post was refused (GroupPostRefusedTest).
        assertTrue(screen.contains("val notice = unavailable ?: refused"))
        assertEquals(1, Regex(Regex.escape("text = notice,")).findAll(screen).count())
        assertFalse(screen.contains("if (acting?.former == true)"))
        // §13: the decline text of a D-group.
        assertTrue(screen.contains("Text(if (distroGroup) DistroGroupTexts.DECLINE_TITLE else \"Decline this group invite?\")"))
        assertTrue(screen.contains("Text(if (distroGroup) DistroGroupTexts.DECLINE_TEXT else \"You won't be able to join this group later.\")"))
    }

    @Test
    fun theInfoSheetOffersOnlyTheLocalDeleteInAFormerDistroGroupAndTheAllDevicesTextsInADGroup() {
        val actions = sheet.indexOf("if (isGroup && acting?.former == true) {")
        assertTrue(actions >= 0)
        assertTrue(actions < sheet.indexOf("Text(DistroGroupTexts.LOCAL_DELETE, style"))
        assertTrue(sheet.indexOf("Text(DistroGroupTexts.LOCAL_DELETE, style") < sheet.indexOf("} else if (isGroup) {"))
        assertTrue(sheet.contains("enabled = acting?.readOnly != true,"))
        assertTrue(sheet.contains("onDeleteFromThisDevice()"))
        assertTrue(sheet.contains("Text(DistroGroupTexts.LOCAL_DELETE_TEXT)"))
        assertTrue(sheet.contains("if (distroGroup) DistroGroupTexts.LEAVE_TITLE else \"Leave this group?\""))
        assertTrue(sheet.contains("DistroGroupTexts.LEAVE_TEXT"))
        assertTrue(sheet.contains("if (isGroup && distroGroup) DistroGroupTexts.DELETE_TITLE"))
        assertTrue(sheet.contains("DistroGroupTexts.DELETE_TEXT"))
        // The local delete is the repository's one path that sends nothing (§8.7).
        assertTrue(screen.contains("app.repository.deleteGroupFromThisDevice(chatId)"))
    }
}
