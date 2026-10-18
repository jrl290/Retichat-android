package com.newendian.retichat.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * A group member's key comes from the contact row, else from the stack's
 * identity cache (an announce), as iOS reads it (`bridge.recalledPublicKey`,
 * RNS `Identity.recall`). Until 10-18 Android read the contact row only, so a
 * group made with a contact added by hash and known from an announce was saved
 * but its invites never left ("invite aborted: missing member public key").
 */
class MemberKeysTest {
    private val key = ByteArray(64) { it.toByte() }
    private val keyHex = key.joinToString("") { "%02x".format(it) }

    @Test
    fun aKeyKnownOnlyFromAnAnnounceIsUsed() {
        assertEquals(keyHex, MemberKeys.pick(stored = null) { key })
        assertEquals(keyHex, MemberKeys.pick(stored = "") { key })
    }

    @Test
    fun theContactRowsKeyComesFirstAndTheCacheIsNotAsked() {
        val stored = "AB".repeat(64)
        var asked = false
        assertEquals("ab".repeat(64), MemberKeys.pick(stored) { asked = true; key })
        assertFalse(asked)
    }

    @Test
    fun aKeyOfTheWrongSizeIsNoKey() {
        assertNull(MemberKeys.pick(stored = "ab".repeat(10)) { null })
        assertNull(MemberKeys.pick(stored = null) { ByteArray(32) })
        assertNull(MemberKeys.pick(stored = null) { null })
    }

    @Test
    fun everyGroupKeyReadGoesThroughIt() {
        val src = File("src/main/kotlin/com/newendian/retichat/data/repository/ChatRepository.kt").readText()
        assertEquals("the contact row's key is read in memberPublicKeyHex only",
            1, Regex("""contactDao\.findByHash\([^)]*\)\?\.publicKeyHex""").findAll(src).count())
        assertEquals(1, Regex("""MemberKeys\.pick\(contactDao\.findByHash\(hashHex\)\?\.publicKeyHex\)""").findAll(src).count())
        assertEquals("createGroupChat's invites, acceptGroupInvite's wait and its accept sync",
            3, Regex("""memberPublicKeyHex\(""").findAll(src).count() - 1)
    }
}
