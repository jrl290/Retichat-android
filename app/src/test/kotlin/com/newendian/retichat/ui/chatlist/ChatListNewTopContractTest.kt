package com.newendian.retichat.ui.chatlist

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The chat list scrolls a chat that takes the top into view when the list was
 * at its top (L84). It needs a running Compose UI to show, so its wiring is
 * checked in the source, as GroupFallbackContractTest does; the emulator run
 * shows it.
 */
class ChatListNewTopContractTest {
    private val source = File("src/main/kotlin/com/newendian/retichat/ui/chatlist/ChatListScreen.kt").readText()

    @Test
    fun aNewTopChatIsScrolledIntoViewWhenTheListWasAtItsTop() {
        assertTrue("the list's state is the one the effect scrolls", source.contains("state = listState,"))
        val atTop = source.indexOf("val wasAtTop = atTop")
        val effect = source.indexOf("LaunchedEffect(topId) {")
        val list = source.indexOf("LazyColumn(")
        assertTrue("read in the composition, before the list lays out the new order",
            atTop in 0 until effect && effect < list)
        assertTrue(source.contains("if (topId != null && wasAtTop) listState.animateScrollToItem(0)"))
        assertTrue(source.contains("val topId = filtered.firstOrNull()?.id"))
    }
}
