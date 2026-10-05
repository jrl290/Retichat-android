package com.newendian.retichat.data.repository

import com.newendian.retichat.data.model.Contact
import com.newendian.retichat.data.model.hexToBytes
import com.newendian.retichat.ui.newchat.pickableContacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * James, 2026-10-02: "Just prevent adding contacts that aren't explicitly
 * added. The group and channel member messages are only accepted by
 * association." A contact exists only when the user adds one; group members,
 * strangers' DMs and distro senders get hidden rows, listed nowhere. The
 * listing itself runs on SQLite in ContactsMigrationTest; the repository and
 * screens need Android to run, so their wiring is checked in the source.
 */
class ExplicitContactsTest {
    private val main = "src/main/kotlin/com/newendian/retichat"
    private val repo = File("$main/data/repository/ChatRepository.kt").readText()
    private val nav = File("$main/ui/navigation/NavGraph.kt").readText()
    private val vm = File("$main/ui/conversation/ConversationViewModel.kt").readText()
    private val dao = File("$main/data/db/dao/ContactDao.kt").readText()
    private val nextMember = Regex("\n    (/\\*\\*|//|private |internal |fun |suspend fun |val |var )")

    private fun body(source: String, name: String): String {
        val start = source.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val end = nextMember.find(source, start)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    @Test
    fun onlyTheUsersAddMakesAContact() {
        val add = body(repo, "addContact")
        assertTrue(add.contains("isContact = true"))
        assertTrue(add.contains("contactDao.setContact(hex)"))
        // Nothing else marks a row a contact.
        assertEquals(1, Regex("isContact = true").findAll(repo).count())
        assertEquals(1, Regex("setContact\\(").findAll(repo).count())
        // Every other row the repository makes is hidden (the entity's default).
        for (fn in listOf("ensureContact", "ensureAllowlistedContact", "renameContact")) {
            assertFalse(fn, body(repo, fn).contains("isContact"))
        }
        // The user adds: New Conversation, a QR code and an lxma:// link all go through it.
        assertTrue(body(repo, "getOrCreateDirectChat").contains("addContact(destHash)"))
    }

    @Test
    fun theContactListIsTheListedRowsAndNamesStillResolveForEveryRow() {
        assertTrue(body(repo, "contacts").contains("contactDao.listedContacts()"))
        assertTrue(body(repo, "nameBook").contains("contactDao.allContacts()"))
        assertTrue(dao.contains("@Query(ContactSql.LISTED)\n    fun listedContacts()"))
        // A stranger's conversation keeps its own name slot.
        assertTrue(vm.contains("repository.peers()"))
        assertFalse(vm.contains("repository.contacts()"))
    }

    @Test
    fun newChatAndNewGroupOfferContactsOnly() {
        val newGroup = nav.substring(nav.indexOf("composable(Routes.NEW_GROUP)"), nav.indexOf("composable(Routes.QR_CODE)"))
        assertTrue(newGroup.contains("repository.contacts()"))
        assertTrue(newGroup.contains("pickableContacts(allContacts, selfHex)"))
        // Not any chat: until 2026-10-05 every DM conversation was offered.
        assertFalse(newGroup.contains("chatPreviews"))
        val newChat = nav.substring(nav.indexOf("composable(Routes.NEW_CHAT)"), nav.indexOf("composable(Routes.NEW_GROUP)"))
        assertTrue(newChat.contains("pickableContacts(allContacts, selfHex)"))
    }

    @Test
    fun thePickerOffersAllowlistedContactsButNeverTheUser() {
        val self = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val friend = Contact("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb".hexToBytes(), "Friend", isAllowlisted = true)
        val filtered = Contact("cccccccccccccccccccccccccccccccc".hexToBytes(), "Old row", isAllowlisted = false)
        val me = Contact(self.hexToBytes(), "Me", isAllowlisted = true)
        assertEquals(listOf(friend), pickableContacts(listOf(friend, filtered, me), self))
    }

    @Test
    fun aStrangersDmAndADistroMessageAreConversationsNotContacts() {
        // The receive paths make hidden rows only.
        assertTrue(body(repo, "onMessageReceived").contains("ensureContact(srcHex)"))
        assertTrue(body(repo, "onDistroMessageReceived").contains("ensureContact(srcHex)"))
        assertTrue(body(repo, "onDistroSentCopy").contains("ensureContact(recipientHex)"))
        assertFalse(body(repo, "onMessageReceived").contains("addContact("))
        assertFalse(body(repo, "onDistroMessageReceived").contains("addContact("))
    }
}
