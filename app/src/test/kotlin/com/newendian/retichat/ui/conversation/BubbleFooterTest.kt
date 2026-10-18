package com.newendian.retichat.ui.conversation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A bubble's footer (the time, the delivery icon, the reactions) is on the
 * sender's side: at the right in a sent bubble, at the left in a received one
 * (James, 2026-10-17), as on iOS and the web. Compose: the source is what is
 * pinned.
 */
class BubbleFooterTest {
    private val screen = File("src/main/kotlin/com/newendian/retichat/ui/conversation/ConversationScreen.kt").readText()

    @Test
    fun theFooterIsOnTheSendersSide() {
        val start = screen.indexOf("private fun MessageRow(")
        assertTrue("MessageRow not found", start >= 0)
        val row = screen.substring(start)
        assertTrue("End for a sent message, Start for a received one",
            row.contains("    val alignment = if (isOut) Alignment.End else Alignment.Start\n"))
        val time = row.indexOf("text = formatMessageTime(msg.timestamp, zone),")
        assertTrue("the footer's time not found", time >= 0)
        val head = row.substring(row.lastIndexOf("Row(", time), time)
        assertTrue("the footer row takes that alignment: $head", head.contains("modifier = Modifier.align(alignment),"))
    }
}
