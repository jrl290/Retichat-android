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
 * holds, the former-distro bar (its text and "Delete from this device") in
 * place of the composer, accept, decline and leave; every self address shown
 * as "You". At start-up, nothing is said (James, 2026-10-11).
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
        // James, 2026-10-11: the only text for a group that cannot act here, the same on every client.
        assertEquals(
            "This group uses your distro address d0d0d0d0…, which is no longer on this device. " +
                "New messages in it don't reach this device, and you can't post, join or leave from here. " +
                "Import that distro again to use the group here.",
            DistroGroupTexts.formerDistro(d),
        )
        assertEquals("the first 8 hex, lowercased, and U+2026", DistroGroupTexts.formerDistro(d.uppercase()), DistroGroupTexts.formerDistro(d))
    }

    @Test
    fun aFormerDistroGroupShowsTheBarInPlaceOfTheComposerAcceptDeclineAndLeave() {
        // One bar, with the text and "Delete from this device" and its confirmation, in both places.
        val at = sheet.indexOf("fun FormerDistroBar(")
        assertTrue("fun FormerDistroBar in ChatInfoSheet.kt", at >= 0)
        val bar = sheet.substring(at)
        val text = bar.indexOf("Text(DistroGroupTexts.formerDistro(own)")
        val button = bar.indexOf("Text(DistroGroupTexts.LOCAL_DELETE, style")
        assertTrue(text >= 0 && button > text)
        assertTrue(bar.contains("title = { Text(\"\${DistroGroupTexts.LOCAL_DELETE}?\") },"))
        assertTrue(bar.contains("text = { Text(DistroGroupTexts.LOCAL_DELETE_TEXT) },"))
        // The conversation: the bar first, then the invite card or the composer.
        val inConversation = screen.indexOf("if (formerOwn != null) {")
        assertTrue(inConversation >= 0)
        assertTrue(screen.indexOf("FormerDistroBar(", inConversation) in inConversation until screen.indexOf("} else if (isPendingInvite) {", inConversation))
        assertEquals(1, Regex(Regex.escape("FormerDistroBar(")).findAll(screen).count())
        // Chat info: the bar in place of Leave.
        val inSheet = sheet.indexOf("if (isGroup && formerOwn != null) {")
        assertTrue(inSheet >= 0 && sheet.indexOf("FormerDistroBar(", inSheet) < sheet.indexOf("} else if (isGroup) {", inSheet))
        // Both delete from this device only: no leave is sent.
        assertEquals(1, Regex(Regex.escape("app.repository.deleteGroupFromThisDevice(chatId)")).findAll(screen).count())
        // The former-distro text is said nowhere else.
        assertEquals(1, Regex(Regex.escape("DistroGroupTexts.formerDistro(")).findAll(sheet).count())
        assertFalse(screen.contains("DistroGroupTexts.formerDistro("))
    }

    @Test
    fun onlyTheDeviceOrTheDistroHeldCanActAndOnlyAFormerDistroIsShown() {
        // James, 2026-10-11: at start-up accept, decline and leave are greyed with no
        // text; only a former distro shows anything (its bar).
        for (signer in GroupSelves.Signer.values()) {
            val acting = GroupActing(distroGroup = signer != GroupSelves.Signer.DEVICE, signer = signer, own = d, selves = setOf(d, device))
            assertEquals(signer.toString(), signer == GroupSelves.Signer.DEVICE || signer == GroupSelves.Signer.DISTRO, acting.canAct)
            assertEquals(signer.toString(), signer == GroupSelves.Signer.FORMER, acting.former)
        }
        assertEquals(
            listOf(GroupSelves.Signer.DEVICE, GroupSelves.Signer.DISTRO, GroupSelves.Signer.FORMER, GroupSelves.Signer.NOT_READY),
            GroupSelves.Signer.values().toList(),
        )
        assertTrue(model.contains("chat?.takeIf { it.isGroup }?.let { repository.groupActing(it) }"))
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
    fun theConversationNeverGreysTheComposerAndGreysAcceptAndDeclineWithNoText() {
        assertTrue(screen.contains("val canAct = acting?.canAct == true"))
        // The composer: field, attach and send, never greyed for the group (a post waits, GroupPostWaitsTest).
        assertTrue(screen.contains("onValueChange = { draft = it },\n                            modifier = Modifier.weight(1f),"))
        assertTrue(screen.contains("IconButton(onClick = { filePickerLauncher.launch(\"*/*\") }) {"))
        assertTrue(screen.contains("enabled = draft.isNotBlank() || pendingAttachmentName != null,"))
        // Accept and decline.
        assertTrue(screen.contains("onClick = { showDeclineConfirm = true },\n                                enabled = canAct,"))
        assertTrue(screen.contains("onClick = { viewModel?.acceptInvite() },\n                                enabled = canAct,"))
        // No text says why: no state is described.
        for (gone in listOf("readOnly", "unavailable", "val notice", "text = notice", "if (acting?.former == true)")) {
            assertFalse(gone, screen.contains(gone))
        }
        // §13: the decline text of a D-group.
        assertTrue(screen.contains("Text(if (distroGroup) DistroGroupTexts.DECLINE_TITLE else \"Decline this group invite?\")"))
        assertTrue(screen.contains("Text(if (distroGroup) DistroGroupTexts.DECLINE_TEXT else \"You won't be able to join this group later.\")"))
    }

    @Test
    fun theInfoSheetOffersOnlyTheLocalDeleteInAFormerDistroGroupAndTheAllDevicesTextsInADGroup() {
        val actions = sheet.indexOf("if (isGroup && formerOwn != null) {")
        assertTrue(actions >= 0)
        assertTrue(actions < sheet.indexOf("FormerDistroBar(own = formerOwn"))
        assertTrue(sheet.indexOf("FormerDistroBar(own = formerOwn") < sheet.indexOf("} else if (isGroup) {"))
        // Leave: greyed, with no text, until the start finishes.
        assertTrue(sheet.contains("onClick = { showLeaveConfirm = true },\n                            enabled = acting?.canAct == true,"))
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
