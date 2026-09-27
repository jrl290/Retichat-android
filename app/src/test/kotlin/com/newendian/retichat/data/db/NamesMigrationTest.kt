package com.newendian.retichat.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
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
        exec("CREATE TABLE IF NOT EXISTS `channel_messages` (`id` TEXT NOT NULL, `channelId` TEXT NOT NULL, `sourceHashHex` TEXT NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `isOutbound` INTEGER NOT NULL, `signatureValidated` INTEGER NOT NULL, `sendState` INTEGER NOT NULL, PRIMARY KEY(`id`))")
    }

    /** Version 11 as Room generates it from the entities (RetichatDatabase_Impl.createAllTables). */
    private val version11 = mapOf(
        "contacts" to "CREATE TABLE `contacts` (`destHashHex` TEXT NOT NULL, `localName` TEXT, `messageName` TEXT, `announceName` TEXT, `publicKeyHex` TEXT, `addedAt` INTEGER NOT NULL, `isAllowlisted` INTEGER NOT NULL, PRIMARY KEY(`destHashHex`))",
        "messages" to "CREATE TABLE `messages` (`id` TEXT NOT NULL, `chatId` TEXT NOT NULL, `senderHashHex` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `isOutbound` INTEGER NOT NULL, `state` INTEGER NOT NULL, `nativeHandle` INTEGER NOT NULL, `progress` REAL NOT NULL, `systemKind` TEXT, PRIMARY KEY(`id`))",
        "channel_senders" to "CREATE TABLE `channel_senders` (`channelId` TEXT NOT NULL, `senderHex` TEXT NOT NULL, `channelName` TEXT, `firstSeenAt` INTEGER NOT NULL, PRIMARY KEY(`channelId`, `senderHex`))",
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

    private data class Row(val local: String?, val message: String?, val announce: String?, val key: String?, val allowlisted: Int)

    private fun contact(hash: String): Row = db.prepareStatement(
        "SELECT localName, messageName, announceName, publicKeyHex, isAllowlisted FROM contacts WHERE destHashHex = ?"
    ).use { st ->
        st.setString(1, hash)
        st.executeQuery().use { rs ->
            rs.next()
            Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5))
        }
    }

    private fun addContact(hash: String, name: String, manual: Boolean, key: String? = null) =
        db.prepareStatement("INSERT INTO contacts VALUES (?, ?, ?, 1, ?)").use {
            it.setString(1, hash); it.setString(2, name); it.setString(3, key); it.setInt(4, if (manual) 1 else 0)
            it.executeUpdate()
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
    fun namesTheUserTypedBecomeLocalNamesAndPlaceholdersAreDropped() {
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
        assertEquals(Row("Mum", null, null, "ab".repeat(64), 1), contact(a))
        assertEquals(Row(null, "Alice", null, null, 1), contact(b))
        assertEquals(Row(null, null, null, null, 1), contact(c))
        assertEquals(Row("11111111", null, null, null, 1), contact(d))
        assertEquals(Row(null, null, null, null, 1), contact(e))
    }

    @Test
    fun aHexNameThatIsNotThisContactsHashIsKept() {
        createVersion10()
        val a = "0123456789abcdef0123456789abcdef"
        addContact(a, "deadbeef", manual = false)
        addContact("abcdefabcdefabcdefabcdefabcdefab", "ABCDEFAB", manual = false)
        migrate()
        assertEquals("deadbeef", contact(a).message)
        assertNull(contact("abcdefabcdefabcdefabcdefabcdefab").message)
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
}
