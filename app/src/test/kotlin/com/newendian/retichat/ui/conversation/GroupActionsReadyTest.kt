package com.newendian.retichat.ui.conversation

import com.newendian.retichat.data.repository.GroupSelves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Start-up (James's UI rulings, 2026-10-11; GROUPS-DISTRO-DESIGN.md §8.7): the
 * app is starting (also a Settings restart, or a start that failed), so which
 * group is the device's, and whether its distro is usable, cannot be told yet.
 * Nothing says so anywhere, as for DMs and channels. Accept, Decline and Leave
 * are greyed until the start finishes (Create too: GroupCreateRefusalTest); the
 * composer is not, because a post waits as a DM does (GroupPostWaitsTest); and
 * "Delete from this device" is never offered then. Until 2026-10-11 the
 * composer was greyed too, and the group said "Retichat isn't running on this
 * device right now".
 */
class GroupActionsReadyTest {
    private val device = "0123456789abcdef0123456789abcdef"
    private val d = "d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0"

    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()
    private val screen = src("ui/conversation/ConversationScreen.kt")
    private val sheet = src("ui/conversation/ChatInfoSheet.kt")

    @Test
    fun atStartUpEveryGroupIsNotReadyNeverAFormerDistrosAndNothingSaysSo() {
        // The device's address is empty until the start has finished, whatever the group and the distro.
        for ((what, persisted, loaded) in listOf(
            Triple("held at upgrade", null, d),
            Triple("a device group", device, null),
            Triple("a D-group with D loaded", d, d),
            Triple("a D-group with no distro loaded", d, null),
        )) {
            assertEquals(what, GroupSelves.Signer.NOT_READY, GroupSelves.signer(persisted, "", loaded))
        }
        // No text describes it: the not-running and not-loaded texts are gone.
        val texts = src("ui/conversation/DistroGroupTexts.kt")
        for (gone in listOf("isn't running", "hasn't loaded", "NOT_RUNNING", "NOT_LOADED", "CREATE_NOT", "fun unavailable(", "fun refused(", "fun createRefused(")) {
            assertFalse(gone, texts.contains(gone))
        }
    }

    @Test
    fun acceptDeclineAndLeaveAreGreyedUntilTheStartFinishesAndTheComposerIsNot() {
        assertTrue(screen.contains("val canAct = acting?.canAct == true"))
        assertTrue(screen.contains("onClick = { showDeclineConfirm = true },\n                                enabled = canAct,"))
        assertTrue(screen.contains("onClick = { viewModel?.acceptInvite() },\n                                enabled = canAct,"))
        assertTrue(sheet.contains("onClick = { showLeaveConfirm = true },\n                            enabled = acting?.canAct == true,"))
        // The composer: a post waits as a DM does, so nothing in it is greyed for the group's sake.
        assertFalse(screen.contains("readOnly"))
        assertTrue(screen.contains("enabled = draft.isNotBlank() || pendingAttachmentName != null,"))
        assertFalse(sheet.contains("readOnly"))
    }

    @Test
    fun theLocalDeleteIsOfferedOnlyInAFormerDistroGroupNeverAtStartUp() {
        // Only a former D's bar offers it, and only a former D shows the bar.
        assertTrue(screen.contains("val formerOwn = acting?.takeIf { it.former }?.own"))
        assertTrue(sheet.contains("val formerOwn = acting?.takeIf { it.former }?.own"))
        assertEquals(1, Regex(Regex.escape("Text(DistroGroupTexts.LOCAL_DELETE,")).findAll(sheet).count())
        assertFalse(screen.contains("DistroGroupTexts.LOCAL_DELETE"))
    }

    @Test
    fun theRepositoryResolvesTheSignerFromWhetherTheStackIsUpAndTheScreenReReadsItWhenThatChanges() {
        val repo = src("data/repository/ChatRepository.kt")
        assertTrue(repo.contains("GroupSelves.signer(chat.ownMember, if (groupActionsReady.value) selfHex() else \"\", DistroManager.signing()?.first?.deliveryHashHex)"))
        assertTrue(repo.contains("_groupActionsReady.value = router != 0L && identity != 0L && selfHash.isNotEmpty()"))
        val model = src("ui/conversation/ConversationViewModel.kt")
        assertTrue(model.contains("combine(repository.chatById(chatId), repository.groupMembers(chatId), DistroManager.state, repository.groupActionsReady)"))
    }

    @Test
    fun theScreenClosesOnlyWhenTheLeaveDeclineOrDeleteWasDone() {
        assertTrue(screen.contains("if (app.repository.leaveGroupChat(chatId)) onBack()"))
        assertTrue(screen.contains("if (app.repository.deleteChat(chatId)) onBack()"))
        assertTrue(screen.contains("viewModel?.declineInvite { onBack() }"))
        val model = src("ui/conversation/ConversationViewModel.kt")
        assertTrue(model.contains("if (repository.declineGroupInvite(chatId)) onDeclined()"))
        val repo = src("data/repository/ChatRepository.kt")
        assertTrue(repo.contains("private suspend fun quitGroup(chatId: String, how: String): Boolean {"))
    }
}
