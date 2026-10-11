package com.newendian.retichat.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A group post refused after Send (James's UI rulings, 2026-10-11;
 * GROUPS-DISTRO-DESIGN.md §8.7): the group's distro went between the screen
 * being drawn and the tap. Nothing is stored or sent, and no notice is shown:
 * the typed text and the attachment stay in the composer, and the former-distro
 * bar then shows. Until 2026-10-11 the screen also said why above the composer,
 * which could only ever say "Retichat isn't running", and then falsely.
 */
class GroupPostRefusedTest {
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
    fun theRepositoryReportsTheRefusalBeforeStoringAnything() {
        assertTrue(repo.contains("suspend fun sendMessage(chatId: String, content: String, attachmentFiles: List<Pair<String, ByteArray>> = emptyList()): GroupPostRefused? {"))
        val send = body(repo, "sendGroupMessage")
        val refused = send.indexOf("return GroupPostRefused(signerKind(chat), GroupSelves.ownMember(chat.ownMember, selfHex()))")
        assertTrue(refused >= 0)
        assertTrue(refused < send.indexOf("messageDao.upsert("))
    }

    @Test
    fun theModelHandsTheRefusalToTheScreenWithNoText() {
        assertTrue(body(model, "send").contains("repository.sendMessage(chatId, text)?.let { onRefused() }"))
        assertTrue(body(model, "sendWithAttachment").contains("repository.sendMessage(chatId, text, listOf(filename to data))?.let { onRefused() }"))
        assertFalse(model.contains("DistroGroupTexts"))
    }

    @Test
    fun theScreenClearsTheComposerBeforeSendingPutsTheDraftBackOnARefusalAndSaysNothing() {
        val tap = screen.indexOf("val sent = draft")
        assertTrue(tap >= 0)
        val clear = screen.indexOf("draft = \"\"", tap)
        val call = screen.indexOf("viewModel?.send(sent, onRefused = keep)", tap)
        assertTrue("cleared before the send: a refusal can only put it back", clear in tap until call)
        assertTrue(screen.indexOf("viewModel?.sendWithAttachment(sent, attachName, attachData, onRefused = keep)", tap) > clear)
        // Put back in front of anything typed since, with the attachment unless another was picked.
        assertTrue(screen.contains("val keep: () -> Unit = {\n                                    draft = sent + draft"))
        assertTrue(screen.contains("if (pendingAttachmentName == null) {\n                                        pendingAttachmentName = attachName"))
        // No notice: nothing remembers why, and nothing above the composer says it.
        for (gone in listOf("var refused", "refused = ", "val notice", "text = notice,", "DistroGroupTexts.unavailable(", "DistroGroupTexts.refused(")) {
            assertFalse(gone, screen.contains(gone))
        }
        assertEquals(0, Regex(Regex.escape("text = unavailable,")).findAll(screen).count())
    }
}
