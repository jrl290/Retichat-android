package com.newendian.retichat.ui.conversation

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A bubble's footer spans the bubble, sent or received: the time at its left,
 * the delivery icon and the reactions at its right (James, 2026-10-17), as on
 * iOS and the web. It takes the bubble's width without widening it; the
 * content above keeps the left. Compose: the source is what is pinned.
 */
class BubbleFooterTest {
    private val screen = File("src/main/kotlin/com/newendian/retichat/ui/conversation/ConversationScreen.kt").readText()

    private fun from(decl: String): String {
        val start = screen.indexOf(decl)
        assertTrue("$decl not found", start >= 0)
        return screen.substring(start)
    }

    @Test
    fun theTimeIsAtTheLeftAndTheRestAtTheRightSentOrReceived() {
        val row = from("private fun MessageRow(")
        assertTrue("the bubble's content is a BubbleColumn",
            row.contains("        ) {\n            BubbleColumn {\n                // Sender name for incoming messages"))
        val time = row.indexOf("text = formatMessageTime(msg.timestamp, zone),")
        assertTrue("the footer's time not found", time >= 0)
        val footer = row.substring(row.lastIndexOf("Row(", time), row.indexOf("\n            }\n", time))
        assertTrue("the footer row is the same for every bubble: $footer", footer.startsWith("Row(verticalAlignment = Alignment.CenterVertically) {"))
        val spacer = footer.indexOf("Spacer(Modifier.weight(1f))")
        assertTrue("the time, then the space that takes the rest of the width", spacer > footer.indexOf("formatMessageTime("))
        assertTrue("then the delivery icon", footer.indexOf("text = stateIcon(msg.state),") > spacer)
        assertTrue("and the reactions", footer.indexOf("text = reactions.joinToString(\"\") { it.emoji },") > spacer)
        assertTrue("the footer is the bubble's last child", footer.trimEnd().endsWith("}\n                }"))
    }

    @Test
    fun theFooterTakesTheBubblesWidthWithoutWideningIt() {
        val layout = from("private fun BubbleColumn(")
        assertTrue(layout.contains("val body = measurables.dropLast(1).map { it.measure(loose) }"))
        assertTrue("as wide as the widest child, the footer's own width included",
            layout.contains("val width = maxOf(body.maxOfOrNull { it.width } ?: 0, footer.maxIntrinsicWidth(Constraints.Infinity))"))
        assertTrue("the footer measured at that width", layout.contains("footer.measure(Constraints.fixedWidth(width))"))
        assertTrue("everything at the left", layout.contains("body.forEach { it.placeRelative(0, y); y += it.height }"))
    }
}
