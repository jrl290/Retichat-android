package com.newendian.retichat.ui.conversation

import com.newendian.retichat.PhotoLocationNotRemoved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Where a photo is made safe to send (James, 2026-10-05: a photo leaves
 * without where it was taken; other files go as given), checked in the
 * source, as the other contract tests here do what needs the native library
 * to run.
 *
 *  - The file picker holds a picked file as it will leave, so the preview
 *    shows what is sent, and one that cannot be made safe is not held.
 *  - ChatRepository.sendMessage, the one entry for what the user composes
 *    (direct, group, distro), cleans every attachment before anything is
 *    stored or sent, and hands the cleaned ones on.
 *  - What is forwarded for others carries no attachment (a group relay is
 *    text only), and a queued message's attachments are the stored files,
 *    which are the cleaned ones: neither goes through the cleaning again, and
 *    neither needs to.
 *  - The view model tells the user why a send did not happen.
 */
class PhotoLocationWiringTest {
    private val main = File("src/main/kotlin/com/newendian/retichat")
    private val sources = main.walkTopDown().filter { it.extension == "kt" }.associate { it.name to it.readText() }
    private val screen = sources.getValue("ConversationScreen.kt")
    private val viewModel = sources.getValue("ConversationViewModel.kt")
    private val repo = sources.getValue("ChatRepository.kt")
    private val groups = sources.getValue("GroupChatManager.kt")

    /** Class members start at four spaces: a KDoc, a comment, or a declaration. */
    private val nextMember = Regex("\n    (/\\*\\*|//|private |internal |fun |suspend fun |val |var )")

    /** The first `fun [name](`, up to the member after it. */
    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val end = nextMember.find(source, start)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    /** The files of the app that contain [text]. */
    private fun filesWith(text: String) = sources.filterValues { it.contains(text) }.keys

    @Test
    fun theMessageIsTheWebs() {
        val e = PhotoLocationNotRemoved("IMG_0003.jpg")
        assertEquals("IMG_0003.jpg: where it was taken could not be removed from it, so it is not sent.", e.message)
        assertEquals("IMG_0003.jpg", e.filename)
    }

    @Test
    fun aPickedFileIsHeldAsItWillLeave() {
        val picker = screen.substring(screen.indexOf("val filePickerLauncher"), screen.indexOf("// Track keyboard visibility"))
        val clean = picker.indexOf("PhotoMetadata.withoutLocation(picked)")
        assertTrue(clean > 0)
        assertTrue("only what was cleaned is held", clean < picker.indexOf("pendingAttachmentData = bytes"))
        assertFalse("the picked bytes are never held as they are", picker.contains("pendingAttachmentData = picked"))
        assertTrue(picker.contains("Toast.makeText(context, PhotoLocationNotRemoved.messageFor(filename), Toast.LENGTH_LONG).show()"))
        assertEquals("the one place a picked file is read", 1, Regex("readBytes\\(\\)").findAll(screen).count())
    }

    @Test
    fun everyAttachmentIsCleanedBeforeAnythingIsStoredOrSent() {
        val send = body(repo, "sendMessage")
        val clean = send.indexOf("PhotoMetadata.withoutLocation(data) ?: throw PhotoLocationNotRemoved(filename)")
        assertTrue(clean > 0)
        assertTrue("before the chat is even looked up", clean < send.indexOf("chatDao.findById(chatId)"))
        assertTrue(send.contains("sendGroupMessage(chat, content, attachments)"))
        assertTrue(send.contains("sendDirectMessage(chatId, destHash, content, attachments)"))
        assertFalse("the bytes as picked go no further", send.contains("attachmentFiles)"))

        // sendMessage is the way in for both sends, and they are where a sent message's attachments are stored.
        assertEquals(1, Regex("sendDirectMessage\\(chatId,").findAll(repo).count())
        assertEquals(1, Regex("sendGroupMessage\\(chat,").findAll(repo).count())
        assertTrue(body(repo, "sendDirectMessage").contains("saveOutboundAttachments(localId, attachments)"))
        assertTrue(body(repo, "sendGroupMessage").contains("saveOutboundAttachments(groupMsgId, attachments)"))
        // Only the view model sends what the user composes.
        assertEquals(setOf("ConversationViewModel.kt"), filesWith("repository.sendMessage("))
        // A message gets an attachment in these places, and in no other: a new one must clean first.
        // ChatRepository hands them to RetichatBridge.messageAddAttachments, the one place that
        // writes them (as FIELD_IMAGE or FIELD_FILE_ATTACHMENTS).
        assertEquals(setOf("ChatRepository.kt", "RetichatBridge.kt"), filesWith("messageAddAttachments("))
        assertEquals(setOf("RetichatBridge.kt"), filesWith("messageAddAttachment("))
        assertEquals(setOf("RetichatBridge.kt"), filesWith("messageSetImage("))
        // GroupChatManager.fanoutMessage, which took attachments and had no caller, is gone
        // (GROUPS-DISTRO-DESIGN.md §8.1: it sent as the device whatever the group's own address).
        assertEquals(0, Regex("fanoutMessage\\(").findAll(groups).count())
        assertEquals(emptySet<String>(), filesWith("fanoutMessage("))
    }

    @Test
    fun whatIsForwardedForOthersAndWhatIsQueuedIsNotCleanedAgain() {
        // A group relay: text only.
        val relay = groups.substring(groups.indexOf("fun performRelay("), groups.indexOf("// ---- Private helpers"))
        assertFalse(relay.contains("ttachment"))
        assertFalse(relay.contains("PhotoMetadata"))
        // The queue's attachments are the files stored when the message was written.
        val flush = body(repo, "flushPendingMessages")
        assertTrue(flush.contains("attachments = queuedAttachments(msg.id)"))
        assertFalse(flush.contains("PhotoMetadata"))
        assertTrue(body(repo, "queuedAttachments").contains("File(it.localPath).readBytes()"))
    }

    @Test
    fun theViewModelSaysWhyASendDidNotHappen() {
        val send = viewModel.substring(viewModel.indexOf("fun sendWithAttachment("), viewModel.indexOf("Lifecycle hook: tell"))
        assertTrue(send.contains("repository.sendMessage(chatId, text, listOf(filename to data))"))
        assertTrue(send.contains("catch (e: PhotoLocationNotRemoved)"))
        assertEquals("only that failure", 1, Regex("catch \\(").findAll(send).count())
        assertTrue(send.contains("Toast.makeText(it, e.message, Toast.LENGTH_LONG).show()"))
    }
}
