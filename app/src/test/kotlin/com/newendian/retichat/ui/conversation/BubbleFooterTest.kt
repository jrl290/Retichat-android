package com.newendian.retichat.ui.conversation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A bubble's footer (the time, the delivery icon, the reactions) is at the
 * bubble's right edge, sent or received (James, 2026-10-17), as on iOS and
 * the web; the content above keeps the left. Compose: the source is what is
 * pinned.
 */
class BubbleFooterTest {
    private val screen = File("src/main/kotlin/com/newendian/retichat/ui/conversation/ConversationScreen.kt").readText()

    @Test
    fun theFooterIsAtTheRightEdgeSentOrReceived() {
        val start = screen.indexOf("private fun MessageRow(")
        assertTrue("MessageRow not found", start >= 0)
        val row = screen.substring(start)
        val footer = row.indexOf("text = formatMessageTime(msg.timestamp, zone),")
        assertTrue("the footer's time not found", footer >= 0)
        val opening = row.lastIndexOf("Row(", footer)
        val head = row.substring(opening, footer)
        assertTrue("the footer row is aligned End: $head", head.contains("modifier = Modifier.align(Alignment.End),"))
        assertTrue("for every bubble, not only a sent one", !head.contains("isOut"))
        // The bubble's content column itself aligns nothing: text and attachments stay at the left.
        assertTrue(row.contains("        ) {\n            Column {\n                // Sender name for incoming messages"))
    }
}
