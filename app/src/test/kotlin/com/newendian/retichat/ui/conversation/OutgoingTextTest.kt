package com.newendian.retichat.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A message's text is sent with both ends trimmed, as iOS and the web send
 * it, its inner line breaks kept. Until 2026-10-05 Android sent a trailing
 * line break as typed, and the other side's preview showed an empty line.
 */
class OutgoingTextTest {
    @Test
    fun bothEndsAreTrimmedAndTheLinesInsideKept() {
        assertEquals("Hello", OutgoingText.of("Hello\n"))
        assertEquals("Hello", OutgoingText.of("  \n\tHello \r\n"))
        assertEquals("one\n\ntwo\n  three", OutgoingText.of("\none\n\ntwo\n  three\n\n"))
        assertEquals("", OutgoingText.of(" \n "))
    }

    private val main = "src/main/kotlin/com/newendian/retichat/ui/conversation"

    @Test
    fun everySendPathSendsTheTrimmedText() {
        val vm = File("$main/ConversationViewModel.kt").readText()
        val send = vm.substring(vm.indexOf("fun send("), vm.indexOf("fun sendWithAttachment("))
        assertTrue(send.contains("val text = OutgoingText.of(content)"))
        assertTrue(send.contains("repository.sendMessage(chatId, text)"))
        assertFalse(send.contains("sendMessage(chatId, content"))
        val withAttachment = vm.substring(vm.indexOf("fun sendWithAttachment("))
        assertTrue(withAttachment.contains("repository.sendMessage(chatId, text, listOf(filename to data))"))
        // A channel post too.
        val screen = File("$main/ConversationScreen.kt").readText()
        assertTrue(screen.contains("val text = OutgoingText.of(draft)\n"))
        assertTrue(screen.contains("app.rfedChannelClient.sendMessage(ch, text)"))
    }
}
