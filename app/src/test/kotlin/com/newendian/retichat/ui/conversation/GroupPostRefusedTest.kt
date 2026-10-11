package com.newendian.retichat.ui.conversation

import com.newendian.retichat.data.repository.GroupPostRefused
import com.newendian.retichat.data.repository.GroupSelves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A group post refused because the stack stopped (or D went) between the
 * composer being enabled and the tap (GROUPS-DISTRO-DESIGN.md §8.7; alignment
 * decision 5, 2026-10-11): nothing is stored or sent, the typed text and the
 * attachment go back in the composer, and the screen says why. Until then the
 * repository refused with only a log line and the draft was lost.
 */
class GroupPostRefusedTest {
    private val d = "d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0"
    private val device = "0123456789abcdef0123456789abcdef"

    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()
    private val repo = src("data/repository/ChatRepository.kt")
    private val model = src("ui/conversation/ConversationViewModel.kt")
    private val screen = src("ui/conversation/ConversationScreen.kt")
    private val nextMember = Regex("\n    (/\\*\\*|//|private |internal |fun |suspend fun |val |var |@)")

    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val end = nextMember.find(source, start)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    @Test
    fun theRefusalSaysWhyInTheGroupViewsWords() {
        assertEquals(DistroGroupTexts.NOT_RUNNING, DistroGroupTexts.refused(GroupPostRefused(GroupSelves.Signer.NOT_READY, device)))
        assertEquals(DistroGroupTexts.NOT_LOADED, DistroGroupTexts.refused(GroupPostRefused(GroupSelves.Signer.NOT_LOADED, d)))
        assertEquals(DistroGroupTexts.formerDistro(d), DistroGroupTexts.refused(GroupPostRefused(GroupSelves.Signer.FORMER, d)))
        // A signer that went between the check and the send (the identity handle gone): the stack is not running.
        assertEquals(DistroGroupTexts.NOT_RUNNING, DistroGroupTexts.refused(GroupPostRefused(GroupSelves.Signer.DEVICE, device)))
    }

    @Test
    fun theRepositoryReportsTheRefusalBeforeStoringAnything() {
        assertTrue(repo.contains("suspend fun sendMessage(chatId: String, content: String, attachmentFiles: List<Pair<String, ByteArray>> = emptyList()): GroupPostRefused? {"))
        val send = body(repo, "sendGroupMessage")
        val refused = send.indexOf("return GroupPostRefused(signerKind(chat), GroupSelves.ownMember(chat.ownMember, selfHex()))")
        assertTrue(refused >= 0)
        assertTrue(refused < send.indexOf("messageDao.upsert("))
    }

    @Test
    fun theModelHandsTheRefusalToTheScreenForTextAndAttachmentAlike() {
        assertTrue(body(model, "send").contains("repository.sendMessage(chatId, text)?.let { onRefused(DistroGroupTexts.refused(it)) }"))
        assertTrue(body(model, "sendWithAttachment").contains("repository.sendMessage(chatId, text, listOf(filename to data))?.let { onRefused(DistroGroupTexts.refused(it)) }"))
    }

    @Test
    fun theScreenClearsTheComposerBeforeSendingAndPutsTheDraftBackOnARefusal() {
        val tap = screen.indexOf("val sent = draft")
        assertTrue(tap >= 0)
        val clear = screen.indexOf("draft = \"\"", tap)
        val call = screen.indexOf("viewModel?.send(sent, onRefused = keep)", tap)
        assertTrue("cleared before the send: a refusal can only put it back", clear in tap until call)
        assertTrue(screen.indexOf("viewModel?.sendWithAttachment(sent, attachName, attachData, onRefused = keep)", tap) > clear)
        // Put back in front of anything typed since, with the attachment unless another was picked.
        assertTrue(screen.contains("draft = sent + draft"))
        assertTrue(screen.contains("if (pendingAttachmentName == null) {\n                                        pendingAttachmentName = attachName"))
        // Said above the composer, until the group can act again.
        assertTrue(screen.contains("refused = why"))
        assertTrue(screen.contains("val notice = unavailable ?: refused"))
        assertTrue(screen.contains("LaunchedEffect(acting) { refused = null }"))
        assertEquals(1, Regex(Regex.escape("text = notice,")).findAll(screen).count())
    }
}
