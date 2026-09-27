package com.newendian.retichat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A stacked notification keeps each message's own sender. Until 2026-09-27 a
 * chat kept one sender name and a group or channel stack credited every
 * earlier message to the latest sender (audit M12).
 */
class NotificationHistoryTest {
    @Test
    fun eachStackedMessageKeepsItsSender() {
        val h = NotificationHistory()
        h.add("group_1", "Alice", "hi", 1)
        h.add("group_1", "Bob", "hello", 2)
        val stack = h.add("group_1", "Carol", "hey", 3)
        assertEquals(listOf("Alice", "Bob", "Carol"), stack.map { it.sender })
        assertEquals(listOf("hi", "hello", "hey"), stack.map { it.content })
    }

    @Test
    fun chatsStackSeparatelyAndClear() {
        val h = NotificationHistory()
        h.add("a", "Alice", "1", 1)
        h.add("b", "Bob", "2", 2)
        h.add("a", "Alice", "3", 3)
        assertEquals(2, h.chatCount)
        assertEquals(3, h.totalMessages)
        h.clear("a")
        assertEquals(1, h.add("a", "Alice", "4", 4).size)
        h.clearAll()
        assertEquals(0, h.chatCount)
    }

    @Test
    fun aGroupIsTitledByItsNameAndADmByItsSenderOnceStacked() {
        val one = listOf(NotificationHistory.Entry("Alice", "hi", 1))
        val two = one + NotificationHistory.Entry("Alice", "again", 2)
        assertEquals("Friends", NotificationHistory.title("Friends", one))
        assertNull(NotificationHistory.title(null, one))
        assertEquals("Alice", NotificationHistory.title(null, two))
    }
}
