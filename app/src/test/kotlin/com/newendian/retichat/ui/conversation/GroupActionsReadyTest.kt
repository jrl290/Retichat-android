package com.newendian.retichat.ui.conversation

import com.newendian.retichat.data.repository.GroupActing
import com.newendian.retichat.data.repository.GroupSelves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Group actions while the stack is not up (starting, failed to start, or
 * stopped): no group send can be signed or go, so none is offered until it is
 * up, as for a D-group whose D is not loaded yet (GROUPS-DISTRO-DESIGN.md §8.1,
 * §8.7, DESIGN_PRINCIPLES §5), and nothing is queued. Until the review of
 * 8d330c4 a device group offered its actions then, the repository refused them
 * with only a log line, and the screen closed as if the user had left; a group
 * whose own address is the device was even shown as a former distro's.
 */
class GroupActionsReadyTest {
    private val device = "0123456789abcdef0123456789abcdef"
    private val d = "d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0"

    /** What the UI is given, with the device's address [deviceNow]: empty while the stack is not up. */
    private fun acting(persisted: String?, deviceNow: String, loadedD: String?, stored: Boolean) =
        GroupActing(distroGroup = false, signer = GroupSelves.signer(persisted, deviceNow, loadedD, stored), own = persisted ?: deviceNow, selves = emptySet())

    @Test
    fun withTheStackNotUpNoGroupOffersAnActionAndNoneReadsAsAFormerDistros() {
        val cases = listOf(
            "held at upgrade (own address: the device, unrecorded)" to acting(null, "", d, stored = true),
            "a device group (own address: the device, recorded)" to acting(device, "", null, stored = false),
            "a device group, with a distro loaded" to acting(device, "", d, stored = true),
            "a D-group with D loaded" to acting(d, "", d, stored = true),
        )
        for ((what, a) in cases) {
            assertTrue("$what: read only (${a.signer})", a.readOnly)
            assertFalse("$what: never the former-distro text and local delete (${a.signer})", a.former)
        }
    }

    @Test
    fun withTheStackUpEachGroupActsAsBefore() {
        assertEquals(GroupSelves.Signer.DEVICE, GroupSelves.signer(null, device, d, distroStored = true))
        assertEquals(GroupSelves.Signer.DEVICE, GroupSelves.signer(device, device, null, distroStored = false))
        assertEquals(GroupSelves.Signer.DISTRO, GroupSelves.signer(d, device, d, distroStored = true))
        assertFalse(acting(device, device, null, stored = false).readOnly)
        assertTrue(acting(d, device, null, stored = false).former)
    }

    // ---- The wiring ----

    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()

    @Test
    fun theRepositoryResolvesTheSignerFromWhetherTheStackIsUpAndTheScreenReReadsItWhenThatChanges() {
        val repo = src("data/repository/ChatRepository.kt")
        assertTrue(repo.contains("GroupSelves.signer(chat.ownMember, if (groupActionsReady.value) selfHex() else \"\","))
        assertTrue(repo.contains("_groupActionsReady.value = router != 0L && identity != 0L && selfHash.isNotEmpty()"))
        val model = src("ui/conversation/ConversationViewModel.kt")
        assertTrue(model.contains("combine(repository.chatById(chatId), repository.groupMembers(chatId), DistroManager.state, repository.groupActionsReady)"))
    }

    @Test
    fun theScreenClosesOnlyWhenTheLeaveDeclineOrDeleteWasDone() {
        val screen = src("ui/conversation/ConversationScreen.kt")
        assertTrue(screen.contains("if (app.repository.leaveGroupChat(chatId)) onBack()"))
        assertTrue(screen.contains("if (app.repository.deleteChat(chatId)) onBack()"))
        assertTrue(screen.contains("viewModel?.declineInvite { onBack() }"))
        val model = src("ui/conversation/ConversationViewModel.kt")
        assertTrue(model.contains("if (repository.declineGroupInvite(chatId)) onDeclined()"))
        val repo = src("data/repository/ChatRepository.kt")
        assertTrue(repo.contains("private suspend fun quitGroup(chatId: String, how: String): Boolean {"))
    }
}
