package com.newendian.retichat.ui.conversation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * REACTIONS-DESIGN.md §7 as the conversation screen is wired: a long press on
 * a bubble opens its actions (the quick reactions where the message takes one,
 * then Copy and Select text wherever there is text); links and attachments
 * keep their own taps; chips under the bubble; no reaction row on a channel
 * post. What the menu looks like on a device is not pinned here.
 */
class MessageActionsWiringTest {
    private val screen = File("src/main/kotlin/com/newendian/retichat/ui/conversation/ConversationScreen.kt").readText()
    private val row = screen.substring(screen.indexOf("private fun MessageRow("), screen.indexOf("private fun MessageActions("))

    @Test
    fun aLongPressOnTheBubbleOpensItsActionsAndTapsStayWithLinksAndAttachments() {
        assertTrue(row.contains("detectTapGestures(onLongPress = {"))
        assertTrue(row.contains("val hasActions = hasText || reactable"))
        // Only a long press: no tap handler on the bubble, so a link's or an attachment's tap is its own.
        val press = row.substring(row.indexOf(".pointerInput(hasActions) {"), row.indexOf(".semantics {"))
        assertFalse(press.contains("onTap"))
        assertFalse(press.contains("onDoubleTap"))
        assertTrue(row.contains("text = linkifyText(text, contentColor),"))
        assertEquals(2, Regex(Regex.escape(".clickable { openAttachment() }")).findAll(row).count())
        // The menu: reactions only where given, Copy and Select text where there is text.
        val actions = screen.substring(screen.indexOf("private fun MessageActions("), screen.indexOf("private fun SelectTextDialog("))
        assertTrue(actions.contains("if (onReact != null) {"))
        assertTrue(actions.contains("Reactions.QUICK.forEach { emoji ->"))
        assertTrue(actions.contains("if (hasText) {"))
        assertTrue(actions.contains("clipboard.setText(AnnotatedString(text))"))
        assertTrue(screen.substring(screen.indexOf("private fun SelectTextDialog(")).contains("SelectionContainer {"))
    }

    @Test
    fun onlyAMessageThatTakesAReactionGetsTheRowAndChannelPostsNever() {
        assertTrue(screen.contains("reactable = !isPendingInvite && Reactions.canReact(isGroup, msg),"))
        assertTrue(row.contains("onReact = viewModel?.takeIf { reactable }"))
        assertTrue(row.contains("Reactions.chips(msg.reactionsJson, reactionSelves)"))
        // The channel screen passes no view model and no reactable: never a reaction row there.
        val channel = screen.substring(screen.indexOf("senderLabel = names.channelPost("))
        val call = channel.substring(0, channel.indexOf(")\n"))
        assertTrue(call.contains("viewModel = null,"))
        assertFalse(call.contains("reactable"))
    }
}
