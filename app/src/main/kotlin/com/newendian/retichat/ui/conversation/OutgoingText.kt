package com.newendian.retichat.ui.conversation

/**
 * A message's text as it is sent: both ends trimmed, as iOS (ConversationView
 * `sendMessage`) and the web (Retichat-js `sendMessage`) send it, so a
 * trailing line break typed or pasted by accident never reaches the other
 * side. The line breaks inside are sent as typed.
 */
object OutgoingText {
    fun of(typed: String): String = typed.trim()
}
