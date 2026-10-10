package com.newendian.retichat.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * Database 13 → 14 on a real SQLite, the same statements the Room migration
 * runs (RFed-spec Group.md, "Member statuses and keys"): a group's creator, the
 * creator's copies of its members' accepts and leaves, and the accepts and
 * leaves held for a key. A missed migration would wipe users' messages
 * (`fallbackToDestructiveMigration`), so the chain is pinned too.
 */
class GroupStatusesMigrationTest {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @After fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    /** Version 13's chats, as Room generated it (no creator). */
    private val version13Chats =
        "CREATE TABLE `chats` (`id` TEXT NOT NULL, `isGroup` INTEGER NOT NULL, `name` TEXT NOT NULL, `memberHashes` TEXT NOT NULL, `groupIdHex` TEXT, `currentRelayerHex` TEXT, `createdAt` INTEGER NOT NULL, `isArchived` INTEGER NOT NULL, PRIMARY KEY(`id`))"

    /** Version 14's tables as Room generates them from the entities. */
    private val version14 = mapOf(
        "chats" to "CREATE TABLE `chats` (`id` TEXT NOT NULL, `isGroup` INTEGER NOT NULL, `name` TEXT NOT NULL, `memberHashes` TEXT NOT NULL, `groupIdHex` TEXT, `currentRelayerHex` TEXT, `createdAt` INTEGER NOT NULL, `isArchived` INTEGER NOT NULL, `creatorHex` TEXT, PRIMARY KEY(`id`))",
        "group_statuses" to "CREATE TABLE `group_statuses` (`chatId` TEXT NOT NULL, `memberHex` TEXT NOT NULL, `action` TEXT NOT NULL, `packed` BLOB NOT NULL, PRIMARY KEY(`chatId`, `memberHex`))",
        "group_held" to "CREATE TABLE `group_held` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `chatId` TEXT NOT NULL, `srcHex` TEXT NOT NULL, `action` TEXT NOT NULL, `msgHash` TEXT NOT NULL, `packed` BLOB NOT NULL)",
    )

    private fun migrate() {
        exec(version13Chats)
        GroupStatusesMigration.STATEMENTS.forEach(::exec)
    }

    /** name, type, notnull, default, pk of each column of [table], by name. */
    private fun info(conn: Connection, table: String): List<String> = conn.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            buildList {
                while (rs.next()) add("${rs.getString("name")}:${rs.getString("type")}:${rs.getInt("notnull")}:${rs.getString("dflt_value")}:${rs.getInt("pk")}")
            }
        }
    }.sorted()

    private fun count(sql: String): Int = db.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getInt(1) } }

    @Test
    fun theMigratedSchemaIsTheOneRoomExpects() {
        migrate()
        DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
            version14.values.forEach { sql -> fresh.createStatement().use { it.execute(sql) } }
            for (table in version14.keys) {
                // Columns by name: ALTER TABLE adds the new column last, Room's CREATE has it where the entity does (last).
                assertEquals(table, info(fresh, table), info(db, table))
            }
        }
    }

    @Test
    fun theVersion14SchemaHereIsWhatRoomGenerates() {
        // exportSchema is off, so Room's generated createAllTables is the schema of record;
        // testDebugUnitTest runs after kspDebugKotlin.
        val impl = File("build/generated/ksp/debug/java/com/newendian/retichat/data/db/RetichatDatabase_Impl.java")
        Assume.assumeTrue(impl.exists())
        val text = impl.readText()
        for ((table, sql) in version14) {
            val room = Regex("CREATE TABLE IF NOT EXISTS `$table` \\([^\"]*").find(text)?.value
            assertEquals(table, sql, room?.replace("IF NOT EXISTS ", ""))
        }
    }

    @Test
    fun theRowsAlreadyHereKeepEverythingAndAGroupHeldBeforeHasNoCreatorRecorded() {
        exec(version13Chats)
        exec("INSERT INTO chats VALUES ('group_a', 1, 'G', 'aa,bb', 'a1', NULL, 100, 0)")
        exec("INSERT INTO chats VALUES ('dm_b', 0, '', 'bb', NULL, NULL, 200, 1)")
        GroupStatusesMigration.STATEMENTS.forEach(::exec)
        assertEquals(2, count("SELECT COUNT(*) FROM chats"))
        assertEquals(2, count("SELECT COUNT(*) FROM chats WHERE creatorHex IS NULL"))
        db.createStatement().use { st ->
            st.executeQuery("SELECT id, isGroup, name, memberHashes, groupIdHex, createdAt, isArchived, creatorHex FROM chats ORDER BY id").use { rs ->
                assertTrue(rs.next())
                assertEquals(listOf("dm_b", "0", "", "bb", null, "200", "1", null), (1..8).map { rs.getString(it) })
                assertTrue(rs.next())
                assertEquals(listOf("group_a", "1", "G", "aa,bb", "a1", "100", "0", null), (1..8).map { rs.getString(it) })
            }
        }
        // A creator is recorded from now on, for a group made or invited into.
        exec("UPDATE chats SET creatorHex = 'cc' WHERE id = 'group_a'")
        assertEquals("cc", db.createStatement().use { st -> st.executeQuery("SELECT creatorHex FROM chats WHERE id = 'group_a'").use { rs -> rs.next(); rs.getString(1) } })
    }

    @Test
    fun theTwoNewTablesStartEmptyAndNothingElseChanges() {
        migrate()
        for (table in listOf("group_statuses", "group_held")) assertEquals(table, 0, count("SELECT COUNT(*) FROM `$table`"))
        assertEquals(setOf("chats", "group_statuses", "group_held"),
            db.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").use { rs ->
                    buildSet { while (rs.next()) add(rs.getString(1)) }
                }
            })
    }

    @Test
    fun aCopyIsKeptPerGroupAndMemberTheNewestReplacesItAndTheyGoWithTheGroup() {
        migrate()
        fun put(chat: String, member: String, action: String, bytes: Int) =
            db.prepareStatement("INSERT OR REPLACE INTO group_statuses VALUES (?, ?, ?, ?)").use {
                it.setString(1, chat); it.setString(2, member); it.setString(3, action); it.setBytes(4, ByteArray(bytes) { 7 }); it.executeUpdate()
            }
        put("g1", "m1", "accept", 300)
        put("g1", "m2", "accept", 300)
        put("g2", "m1", "accept", 300)
        put("g1", "m1", "leave", 250)
        assertEquals(3, count("SELECT COUNT(*) FROM group_statuses"))
        assertEquals("leave", db.createStatement().use { st -> st.executeQuery("SELECT action FROM group_statuses WHERE chatId = 'g1' AND memberHex = 'm1'").use { rs -> rs.next(); rs.getString(1) } })
        assertEquals(250, db.createStatement().use { st -> st.executeQuery("SELECT LENGTH(packed) FROM group_statuses WHERE chatId = 'g1' AND memberHex = 'm1'").use { rs -> rs.next(); rs.getInt(1) } })
        // One row per group and member: a plain second insert is refused.
        var refused = false
        try {
            exec("INSERT INTO group_statuses VALUES ('g1', 'm2', 'leave', x'00')")
        } catch (e: SQLException) {
            refused = true
        }
        assertTrue(refused)
        // The copies go with the group; another group's stay.
        exec("DELETE FROM group_statuses WHERE chatId = 'g1'")
        assertEquals(1, count("SELECT COUNT(*) FROM group_statuses"))
    }

    @Test
    fun heldEntriesKeepTheOrderTheyCameInAndANewOneNeverTakesAnOldOnesPlace() {
        migrate()
        fun hold(src: String) = db.prepareStatement("INSERT INTO group_held (chatId, srcHex, action, msgHash, packed) VALUES ('g1', ?, 'accept', ?, x'00')").use {
            it.setString(1, src); it.setString(2, "hash-$src"); it.executeUpdate()
        }
        fun order(): List<String> = db.createStatement().use { st ->
            st.executeQuery("SELECT srcHex FROM group_held ORDER BY rowId ASC").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
        }
        hold("a"); hold("b"); hold("c")
        assertEquals(listOf("a", "b", "c"), order())
        exec("DELETE FROM group_held WHERE srcHex = 'c'")
        hold("d")
        assertEquals("AUTOINCREMENT: an id is never used twice", listOf("a", "b", "d"), order())
        assertEquals(4, count("SELECT MAX(rowId) FROM group_held"))
        exec("DELETE FROM group_held WHERE chatId = 'g1'")
        assertEquals(0, count("SELECT COUNT(*) FROM group_held"))
    }

    @Test
    fun theMigrationIsRegisteredAndEveryEarlierStepIsStillInTheChain() {
        val database = File("src/main/kotlin/com/newendian/retichat/data/db/RetichatDatabase.kt").readText()
        // Database 15 (DistroSyncMigration) came after it: the version is its, the step stays.
        assertFalse(database.contains("version = GroupStatusesMigration.TO,"))
        assertEquals(GroupStatusesMigration.TO, DistroSyncMigration.FROM)
        assertTrue(database.contains("MIGRATION_13_14"))
        // Every earlier step is still in the chain, so no version wipes the messages.
        for (step in listOf("MIGRATION_9_10", "MIGRATION_10_11", "MIGRATION_11_12", "MIGRATION_12_13", "MIGRATION_13_14")) {
            assertTrue(step, Regex("addMigrations\\([^)]*$step[^)]*\\)").containsMatchIn(database))
        }
        assertFalse(database.contains("version = DistroChannelsMigration.TO,"))
        assertEquals(14, GroupStatusesMigration.TO)
        assertEquals(13, GroupStatusesMigration.FROM)
        assertEquals(DistroChannelsMigration.TO, GroupStatusesMigration.FROM)
        for (entity in listOf("GroupStatusEntity::class", "GroupHeldEntity::class")) assertTrue(entity, database.contains(entity))
        assertTrue(database.contains("abstract fun groupStatusDao(): GroupStatusDao"))
        // The creator is a column of the chat, nullable, last.
        val entities = File("src/main/kotlin/com/newendian/retichat/data/db/entity/Entities.kt").readText()
        assertTrue(entities.contains("val creatorHex: String? = null,"))
        assertNull(Regex("val creatorHex: String\\s*[,)]").find(entities))
    }
}
