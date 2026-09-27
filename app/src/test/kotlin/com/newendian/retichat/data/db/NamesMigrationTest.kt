package com.newendian.retichat.data.db

import org.junit.After
import org.junit.Assume
import com.newendian.retichat.names.DisplayNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Database 10 → 11 (DISPLAY_NAMES.md §5.4), run on a real SQLite: the same
 * statements the Room migration executes.
 */
class NamesMigrationTest {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @After fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    /** The version-10 tables the migration touches, as Room 2.6 created them. */
    private fun createVersion10() {
        exec("CREATE TABLE IF NOT EXISTS `contacts` (`destHashHex` TEXT NOT NULL, `displayName` TEXT NOT NULL, `publicKeyHex` TEXT, `addedAt` INTEGER NOT NULL, `isNameManual` INTEGER NOT NULL, PRIMARY KEY(`destHashHex`))")
        exec("CREATE TABLE IF NOT EXISTS `messages` (`id` TEXT NOT NULL, `chatId` TEXT NOT NULL, `senderHashHex` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `isOutbound` INTEGER NOT NULL, `state` INTEGER NOT NULL, `nativeHandle` INTEGER NOT NULL, `progress` REAL NOT NULL, PRIMARY KEY(`id`))")
        exec("CREATE TABLE IF NOT EXISTS `chats` (`id` TEXT NOT NULL, `isGroup` INTEGER NOT NULL, `name` TEXT NOT NULL, `memberHashes` TEXT NOT NULL, `groupIdHex` TEXT, `currentRelayerHex` TEXT, `createdAt` INTEGER NOT NULL, `isArchived` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        exec("CREATE TABLE IF NOT EXISTS `channel_messages` (`id` TEXT NOT NULL, `channelId` TEXT NOT NULL, `sourceHashHex` TEXT NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `isOutbound` INTEGER NOT NULL, `signatureValidated` INTEGER NOT NULL, `sendState` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    }

    /** Version 11 as Room generates it from the entities (RetichatDatabase_Impl.createAllTables). */
    private val version11 = mapOf(
        "contacts" to "CREATE TABLE `contacts` (`destHashHex` TEXT NOT NULL, `localName` TEXT, `messageName` TEXT, `messageNameAt` REAL, `announceName` TEXT, `legacyName` TEXT, `publicKeyHex` TEXT, `addedAt` INTEGER NOT NULL, `isAllowlisted` INTEGER NOT NULL, PRIMARY KEY(`destHashHex`))",
        "messages" to "CREATE TABLE `messages` (`id` TEXT NOT NULL, `chatId` TEXT NOT NULL, `senderHashHex` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `isOutbound` INTEGER NOT NULL, `state` INTEGER NOT NULL, `nativeHandle` INTEGER NOT NULL, `progress` REAL NOT NULL, `systemKind` TEXT, PRIMARY KEY(`id`))",
        "channel_senders" to "CREATE TABLE `channel_senders` (`channelId` TEXT NOT NULL, `senderHex` TEXT NOT NULL, `channelName` TEXT, `firstSeenAt` INTEGER NOT NULL, `nameAt` INTEGER NOT NULL, PRIMARY KEY(`channelId`, `senderHex`))",
        "channel_name_state" to "CREATE TABLE `channel_name_state` (`channelId` TEXT NOT NULL, `lastDigestHex` TEXT NOT NULL, `lastIncludedAt` INTEGER NOT NULL, PRIMARY KEY(`channelId`))",
    )

    /** name, type, notnull, pk of each column: what Room's schema check compares. */
    private fun columns(conn: Connection, table: String): List<String> =
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                buildList {
                    while (rs.next()) add("${rs.getString("name")}:${rs.getString("type")}:${rs.getInt("notnull")}:${rs.getInt("pk")}")
                }
            }
        }

    private fun migrate() = NamesMigration.STATEMENTS.forEach(::exec)

    /** A migrated contact; `messageName`, `messageNameAt` and `announceName` must all be empty. */
    private data class Row(val local: String?, val legacy: String?, val key: String?, val allowlisted: Int)

    private fun contact(hash: String): Row = db.prepareStatement(
        "SELECT localName, legacyName, publicKeyHex, isAllowlisted, messageName, messageNameAt, announceName FROM contacts WHERE destHashHex = ?"
    ).use { st ->
        st.setString(1, hash)
        st.executeQuery().use { rs ->
            rs.next()
            // §5.4: nothing migrates into messageName (it would outrank a
            // current announce name for good) and no order is inherited.
            assertNull(rs.getString(5)); assertNull(rs.getObject(6)); assertNull(rs.getString(7))
            Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4))
        }
    }

    private fun addContact(hash: String, name: String, manual: Boolean, key: String? = null) =
        db.prepareStatement("INSERT INTO contacts VALUES (?, ?, ?, 1, ?)").use {
            it.setString(1, hash); it.setString(2, name); it.setString(3, key); it.setInt(4, if (manual) 1 else 0)
            it.executeUpdate()
        }

    private fun addChat(id: String, name: String, group: Boolean = false) =
        db.prepareStatement("INSERT INTO chats VALUES (?, ?, ?, ?, NULL, NULL, 7, 0)").use {
            it.setString(1, id); it.setInt(2, if (group) 1 else 0); it.setString(3, name); it.setString(4, id.removePrefix("dm_"))
            it.executeUpdate()
        }

    private fun contactCount(): Int = db.createStatement().use { st ->
        st.executeQuery("SELECT COUNT(*) FROM contacts").use { rs -> rs.next(); rs.getInt(1) }
    }

    @Test
    fun theMigratedSchemaIsTheOneRoomExpects() {
        createVersion10()
        migrate()
        val fresh = DriverManager.getConnection("jdbc:sqlite::memory:")
        fresh.use { f ->
            version11.values.forEach { sql -> f.createStatement().use { it.execute(sql) } }
            for (table in version11.keys) assertEquals(table, columns(f, table), columns(db, table))
        }
    }

    @Test
    fun theVersion11SchemaHereIsWhatRoomGenerates() {
        // exportSchema is off, so Room's generated createAllTables is the
        // schema of record; testDebugUnitTest runs after kspDebugKotlin.
        val impl = File("build/generated/ksp/debug/java/com/newendian/retichat/data/db/RetichatDatabase_Impl.java")
        Assume.assumeTrue(impl.exists())
        val generated = impl.readText()
        for ((table, sql) in version11) {
            val room = Regex("CREATE TABLE IF NOT EXISTS `$table` \\([^\"]*").find(generated)?.value
            assertEquals(table, sql, room?.replace("IF NOT EXISTS ", ""))
        }
    }

    @Test
    fun namesTheUserTypedBecomeLocalNamesOthersLegacyNamesAndPlaceholdersAreDropped() {
        createVersion10()
        val a = "0123456789abcdef0123456789abcdef"
        val b = "fedcba9876543210fedcba9876543210"
        val c = "aaaaaaaabbbbbbbbccccccccdddddddd"
        val d = "11111111222222223333333344444444"
        val e = "99999999888888887777777766666666"
        addContact(a, "Mum", manual = true, key = "ab".repeat(64))
        addContact(b, "Alice", manual = false)
        addContact(c, "aaaaaaaa", manual = false)        // the 8-hex placeholder
        addContact(d, "11111111", manual = true)         // typed by the user: kept
        addContact(e, "  ", manual = false)
        migrate()
        assertEquals(Row("Mum", null, "ab".repeat(64), 1), contact(a))
        assertEquals(Row(null, "Alice", null, 1), contact(b))
        assertEquals(Row(null, null, null, 1), contact(c))
        assertEquals(Row("11111111", null, null, 1), contact(d))
        assertEquals(Row(null, null, null, 1), contact(e))
    }

    /** §5.4's one placeholder list, and names that are not on it. */
    private val placeholders = listOf(
        "deadbeef", "DEADBEEF", "01234567", "?01234567", "01234567\u2026", "?01234567\u2026",
        "0123456789abcdef", "0123456789abcdef0123456789abcdef", "?0123456789abcdef0123456789abcdef",
        " 01234567 ", "Retichat", "RETICHAT", "retichat web", "Retichat Web", "Anonymous Peer", " anonymous PEER ",
    )
    private val names = listOf(
        "Alice", "0123456", "?0123456", "0123456\u2026", "0123456789abcdef0123456789abcdef0", "0123456g",
        "??01234567", "01234567...", "01234567?", "Retichat fan", "Retichat Webb", "Anonymous", "dead beef", "",
    )

    @Test
    fun thePlaceholderListIsTheSpecsInSqlAndKotlin() {
        for (p in placeholders) assertTrue(p, DisplayNames.isPlaceholder(p))
        for (n in names) assertFalse(n, DisplayNames.isPlaceholder(n))
        // The migration's SQL gives the same answer as the Kotlin rule.
        db.prepareStatement("SELECT ${NamesMigration.placeholderSql("?1")}").use { st ->
            for (v in placeholders + names) {
                st.setString(1, v)
                st.executeQuery().use { rs -> rs.next(); assertEquals(v, DisplayNames.isPlaceholder(v), rs.getInt(1) == 1) }
            }
        }
    }

    @Test
    fun everyHashFormIsAPlaceholderWhateverHashItIs() {
        // §5.4: 8 to 32 hex, with or without "?" or "…", not only a prefix
        // of this contact's own hash (the web's "?hash" names, picker forms).
        createVersion10()
        val a = "0123456789abcdef0123456789abcdef"
        val b = "fedcba9876543210fedcba9876543210"
        val c = "aaaaaaaabbbbbbbbccccccccdddddddd"
        val d = "11111111222222223333333344444444"
        addContact(a, "deadbeef", manual = false)
        addContact(b, "?FEDCBA98", manual = false)
        addContact(c, "12345678\u2026", manual = false)
        addContact(d, "1234567", manual = false)           // 7 hex: a name
        migrate()
        assertEquals(Row(null, null, null, 1), contact(a))
        assertEquals(Row(null, null, null, 1), contact(b))
        assertEquals(Row(null, null, null, 1), contact(c))
        assertEquals(Row(null, "1234567", null, 1), contact(d))
    }

    @Test
    fun messagesKeepTheirRowsAndChannelPostersAreSeeded() {
        createVersion10()
        exec("INSERT INTO messages VALUES ('m1', 'dm_x', 'x', 'hi', 5, 0, 8, 0, 0.0)")
        exec("INSERT INTO channel_messages VALUES ('1', 'ch', 'AA', '', 'a', 30, 0, 1, 1)")
        exec("INSERT INTO channel_messages VALUES ('2', 'ch', 'aa', '', 'b', 10, 0, 1, 1)")
        exec("INSERT INTO channel_messages VALUES ('3', 'ch', 'me', '', 'c', 20, 1, 1, 1)")
        migrate()
        db.createStatement().use { st ->
            st.executeQuery("SELECT content, systemKind FROM messages WHERE id = 'm1'").use { rs ->
                rs.next(); assertEquals("hi", rs.getString(1)); assertNull(rs.getString(2))
            }
            st.executeQuery("SELECT senderHex, channelName, firstSeenAt FROM channel_senders").use { rs ->
                val rows = buildList { while (rs.next()) add("${rs.getString(1)}|${rs.getString(2)}|${rs.getLong(3)}") }
                // One row per poster (lowercased), our own posts left out.
                assertEquals(listOf("aa|null|10"), rows)
            }
            st.executeQuery("SELECT COUNT(*) FROM channel_name_state").use { rs ->
                rs.next(); assertEquals(0, rs.getInt(1))
            }
        }
    }

    @Test
    fun appPlaceholdersReceivedAsNamesAreDropped() {
        // §1: no placeholder names. The sender's own migration turns its
        // "Retichat" into no name and an unset sender never sends a clear, so
        // a migrated placeholder would stay for good.
        createVersion10()
        val a = "0123456789abcdef0123456789abcdef"
        val b = "fedcba9876543210fedcba9876543210"
        val c = "aaaaaaaabbbbbbbbccccccccdddddddd"
        val d = "11111111222222223333333344444444"
        addContact(a, "Retichat", manual = false)
        addContact(b, " retichat web ", manual = false)
        addContact(c, "Anonymous Peer", manual = false)
        addContact(d, "Retichat", manual = true)          // typed by the user: kept
        migrate()
        assertEquals(Row(null, null, null, 1), contact(a))
        assertEquals(Row(null, null, null, 1), contact(b))
        assertEquals(Row(null, null, null, 1), contact(c))
        assertEquals(Row("Retichat", null, null, 1), contact(d))
    }

    @Test
    fun aRenameOnlyTheDmChatStillHoldsBecomesTheLocalName() {
        // Old build: rename to "Mum" wrote the contact and the chat; re-adding
        // the contact (QR code) reset the contact to its hash and kept the chat.
        createVersion10()
        val a = "0123456789abcdef0123456789abcdef"
        val b = "fedcba9876543210fedcba9876543210"
        val c = "aaaaaaaabbbbbbbbccccccccdddddddd"
        val d = "11111111222222223333333344444444"
        val e = "99999999888888887777777766666666"
        val f = "abababababababababababababababab"
        addContact(a, "01234567", manual = false); addChat("dm_$a", "Mum")
        addContact(b, "Jane", manual = false); addChat("dm_$b", "Mum")        // reset, then a name arrived
        addContact(c, "Alice", manual = false); addChat("dm_$c", "Alice")     // the chat just mirrored it
        addContact(d, "11111111", manual = false); addChat("dm_$d", "11111111")
        addContact(e, "99999999", manual = false); addChat("dm_$e", "Retichat")
        addContact(f, "Dad", manual = true); addChat("dm_$f", "Pop")          // the contact's rename wins
        migrate()
        assertEquals(Row("Mum", null, null, 1), contact(a))
        assertEquals(Row("Mum", "Jane", null, 1), contact(b))
        assertEquals(Row(null, "Alice", null, 1), contact(c))
        assertEquals(Row(null, null, null, 1), contact(d))
        assertEquals(Row(null, null, null, 1), contact(e))
        assertEquals(Row("Dad", null, null, 1), contact(f))
    }

    @Test
    fun aDmChatWithNoContactGetsOneCarryingItsName() {
        // A distro sent-copy created the chat without a contact; renaming it
        // wrote only chats.name.
        createVersion10()
        val a = "0123456789abcdef0123456789abcdef"
        val b = "fedcba9876543210fedcba9876543210"
        val c = "aaaaaaaabbbbbbbbccccccccdddddddd"
        addChat("dm_$a", "Carol")
        addChat("dm_$b", "fedcba98")
        addContact(c, "Alice", manual = false); addChat("dm_$c", "Alice")
        addChat("group_0123456789abcdef", "Friends", group = true)
        addChat("dm_notahash", "Nope")
        migrate()
        assertEquals(Row("Carol", null, null, 0), contact(a))
        assertEquals(Row(null, null, null, 0), contact(b))
        assertEquals(Row(null, "Alice", null, 1), contact(c))
        assertEquals(3, contactCount())
    }
}
