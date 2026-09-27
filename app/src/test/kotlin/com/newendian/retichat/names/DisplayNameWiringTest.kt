package com.newendian.retichat.names

import com.newendian.retichat.service.UserPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DISPLAY_NAMES.md wiring that needs the native library or a device to run,
 * checked in the source (as SendOrderingContractTest does), plus the settings
 * migration.
 */
class DisplayNameWiringTest {
    private fun src(path: String) = File("src/main/kotlin/com/newendian/retichat/$path").readText()

    private val bridge = src("bridge/RetichatBridge.kt")
    private val stack = src("service/StackRuntime.kt")
    private val repo = src("data/repository/ChatRepository.kt")
    private val channels = src("service/RfedChannelClient.kt")

    @Test
    fun theOldDisplayNameBecomesTheMessageNameAndThePlaceholderNone() {
        assertEquals("", UserPreferences.migratedMessageName(null))
        assertEquals("", UserPreferences.migratedMessageName("  "))
        assertEquals("", UserPreferences.migratedMessageName("Retichat"))
        assertEquals("", UserPreferences.migratedMessageName(" Retichat "))
        assertEquals("Alice", UserPreferences.migratedMessageName(" Alice "))
        assertEquals("retichat fan", UserPreferences.migratedMessageName("retichat fan"))
        // No "Retichat" fallback is left anywhere.
        assertFalse(src("service/UserPreferences.kt").contains("DEFAULT_DISPLAY_NAME"))
    }

    @Test
    fun theDeliveryCallbackHasTheNineArgumentSignatureTheNativeSideCalls() {
        // ([B[B[BLjava/lang/String;Ljava/lang/String;DZI[B)V: an old 8-argument
        // Kotlin gets NoSuchMethodError and receives nothing.
        val cb = bridge.substring(bridge.indexOf("interface MessageCallback"), bridge.indexOf("interface AnnounceCallback"))
        assertTrue(Regex("signatureValid: Boolean,\\s*unverifiedReason: Int,\\s*fieldsRaw: ByteArray").containsMatchIn(cb))
    }

    @Test
    fun theChannelPackCarriesTheNameState() {
        assertTrue(Regex("private external fun nativeChannelLxmPack\\([^)]*displayNameState: Int, displayName: ByteArray\\?").containsMatchIn(bridge))
        assertTrue(channels.contains("content.toByteArray(), ByteArray(0), postName"))
        // Decided once per post; recorded only after RFed took it.
        val trySend = channels.substring(channels.indexOf("private suspend fun trySend("))
        assertTrue(trySend.indexOf("recordPostName(") > trySend.indexOf("appLinkSendData("))
    }

    @Test
    fun theAnnounceNameIsSetBeforeTheFirstAnnounce() {
        val register = stack.indexOf("routerRegisterDelivery(")
        val announceName = stack.indexOf("routerSetAnnounceDisplayName(")
        val publish = stack.indexOf("transportPublishDestination(selfDestHash")
        assertTrue(register in 0 until announceName)
        assertTrue(announceName < publish)
        assertTrue(stack.contains("UserPreferences.getMessageDisplayName(app)"))
    }

    @Test
    fun contactsAnnouncesAreWatchedSoTheirAnnounceNamesArrive() {
        // Transport drops unwatched announces by default; its watch list is
        // in memory, so it is rebuilt at every start.
        assertTrue(stack.contains("repo.watchContactAnnounces()"))
        assertTrue(repo.contains("RetichatBridge.watchAnnounce(hex.hexToBytes())"))
        for (fn in listOf("addContact", "ensureContact", "ensureAllowlistedContact")) {
            val start = repo.indexOf("fun $fn(")
            val body = repo.substring(start, repo.indexOf("\n    }", start))
            assertTrue(fn, body.contains("watchAnnounces(hex)"))
        }
    }

    @Test
    fun aDistroSentCopyGivesItsRecipientAContactSoTheChatCanBeNamed() {
        // iOS handleDistroSentCopy: ensureContact + watchAnnounce. Without a
        // contact row the chat title stays the short hash and the recipient's
        // announces are ignored as from an unknown destination.
        val start = repo.indexOf("fun onDistroSentCopy(")
        val body = repo.substring(start, repo.indexOf("\n    }", start))
        val ensure = body.indexOf("ensureContact(recipientHex)")
        assertTrue(ensure >= 0)
        assertTrue(ensure < body.indexOf("chatDao.upsert("))
        assertFalse(body.contains("ensureAllowlistedContact"))
    }

    @Test
    fun namesAreDecodedByRustAndAcceptedByTheSignatureRule() {
        assertFalse(src("bridge/LxmfFields.kt").contains("FIELD_SENDER_NAME"))
        assertTrue(repo.contains("NameField.fromTrailer(RetichatBridge.displayNameDecode(fieldsRaw))"))
        // §5.2 order on both accept paths (router: DMs, groups, relayed
        // copies; distro unwrap): the message's LXMF timestamp goes in.
        assertEquals(2, Regex("acceptMessageName\\(srcHex, nameField, unverifiedReason, timestamp\\)").findAll(repo).count())
        val accept = repo.substring(repo.indexOf("private suspend fun acceptMessageName("), repo.indexOf("/** The privacy filter's answer"))
        assertTrue(accept.contains("contact?.messageNameAt"))
        assertTrue(accept.contains("contactDao.acceptMessageName("))
        assertFalse(repo.contains("setMessageName("))
        // An announce carrying a name drops the legacy name.
        assertTrue(repo.contains("DisplayNames.acceptAnnounceName(existing.announceName, displayName, existing.legacyName)"))
        // Distro unwrap carries the name state and the signature result.
        val distro = src("service/RfedDistroClient.kt")
        assertTrue(distro.contains("\"display_name_state\"") && distro.contains("\"unverified_reason\""))
        assertTrue(distro.contains("distroAnnouncePayload(distro, announceName)"))
    }

    @Test
    fun noSurfaceFreezesANameOrShowsAHashPrefixAsOne() {
        val ui = listOf(
            "ui/conversation/ConversationScreen.kt",
            "ui/conversation/ChatInfoSheet.kt",
            "ui/chatlist/ChatListViewModel.kt",
            "ui/navigation/NavGraph.kt",
            "MainActivity.kt",
            "service/RfedChannelClient.kt",
        ).map(::src)
        for (s in ui + repo) {
            assertFalse(s.contains("contactNames"))
            assertFalse(Regex("(senderName|displayName|name) = [^\\n]*\\.take\\((8|12)\\)").containsMatchIn(s))
        }
        // DM titles are never snapshotted into chats.name any more.
        val rename = repo.substring(repo.indexOf("suspend fun renameContact("), repo.indexOf("private suspend fun ensureContact("))
        assertFalse(rename.contains("updateChatName"))
        // A rename is cleaned by the Rust cleaner (§3), like the own names;
        // nothing left (an empty rename included) clears the local name.
        assertTrue(rename.contains("DisplayNames.localName(newName)"))
        assertTrue(rename.contains("RetichatBridge.displayNameClean(raw, announce = false)"))
        assertTrue(rename.contains("contactDao.setLocalName(hex, name)"))
        // renameContact is the only writer of localName.
        for (path in listOf("ui/conversation/ConversationScreen.kt", "ui/conversation/ChatInfoSheet.kt",
                "ui/conversation/ConversationViewModel.kt", "ui/navigation/NavGraph.kt", "MainActivity.kt")) {
            assertFalse(path, src(path).contains("setLocalName("))
        }
        assertEquals(1, Regex("setLocalName\\(").findAll(repo).count())
        assertFalse(repo.contains("name = contact?.displayName"))
    }
}
