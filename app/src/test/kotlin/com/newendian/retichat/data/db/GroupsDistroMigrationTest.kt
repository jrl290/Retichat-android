package com.newendian.retichat.data.db

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Database 15 → 16 on a real SQLite, the same statements the Room migration
 * runs (GROUPS-DISTRO-DESIGN.md §18.2 item 3; RFed SPEC §17.14): a group's own
 * address (`chats.ownMember`, null for every group held before it: the device,
 * §8.1 "held at upgrade") and the held arrivals of §6.5. A missed migration would
 * wipe users' messages (`fallbackToDestructiveMigration`), so the chain is
 * pinned too. v0.1.10 shipped database 14; this branch had 15 (the sync proof).
 */
class GroupsDistroMigrationTest {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @After fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    /** Version 15's chats, as Room generated it (GroupStatusesMigration added the creator at 14; 15 left it alone). */
    private val version15Chats =
        "CREATE TABLE `chats` (`id` TEXT NOT NULL, `isGroup` INTEGER NOT NULL, `name` TEXT NOT NULL, `memberHashes` TEXT NOT NULL, `groupIdHex` TEXT, `currentRelayerHex` TEXT, `createdAt` INTEGER NOT NULL, `isArchived` INTEGER NOT NULL, `creatorHex` TEXT, PRIMARY KEY(`id`))"

    /** Version 16's tables as Room generates them from the entities. */
    private val version16 = mapOf(
        "chats" to "CREATE TABLE `chats` (`id` TEXT NOT NULL, `isGroup` INTEGER NOT NULL, `name` TEXT NOT NULL, `memberHashes` TEXT NOT NULL, `groupIdHex` TEXT, `currentRelayerHex` TEXT, `createdAt` INTEGER NOT NULL, `isArchived` INTEGER NOT NULL, `creatorHex` TEXT, `ownMember` TEXT, PRIMARY KEY(`id`))",
        "group_arrivals" to "CREATE TABLE `group_arrivals` (`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `groupId` TEXT NOT NULL, `srcHex` TEXT NOT NULL, `msgHash` TEXT NOT NULL, `blobDigest` TEXT NOT NULL, `validated` INTEGER NOT NULL, `distro` TEXT NOT NULL, `isPostSync` INTEGER NOT NULL, `blob` BLOB NOT NULL)",
    )

    /** name, type, notnull, default, pk of each column of [table], in order. */
    private fun info(conn: Connection, table: String): List<String> = conn.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            buildList {
                while (rs.next()) add("${rs.getString("name")}:${rs.getString("type")}:${rs.getInt("notnull")}:${rs.getString("dflt_value")}:${rs.getInt("pk")}")
            }
        }
    }

    private fun migrate() {
        exec(version15Chats)
        GroupsDistroMigration.STATEMENTS.forEach(::exec)
    }

    @Test
    fun theMigratedTablesAreTheOnesRoomExpects() {
        migrate()
        DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
            version16.values.forEach { sql -> fresh.createStatement().use { it.execute(sql) } }
            // ALTER TABLE adds the column last, where the entity has it, so even the order matches.
            for (table in version16.keys) assertEquals(table, info(fresh, table), info(db, table))
        }
    }

    @Test
    fun theVersion16TablesHereAreWhatRoomGenerates() {
        // exportSchema is off, so Room's generated createAllTables is the schema of record;
        // testDebugUnitTest runs after kspDebugKotlin.
        val impl = File("build/generated/ksp/debug/java/com/newendian/retichat/data/db/RetichatDatabase_Impl.java")
        Assume.assumeTrue(impl.exists())
        val text = impl.readText()
        for ((table, sql) in version16) {
            val room = Regex("CREATE TABLE IF NOT EXISTS `$table` \\([^\"]*").find(text)?.value
            assertEquals(table, sql, room?.replace("IF NOT EXISTS ", ""))
        }
    }

    @Test
    fun aGroupHeldBeforeKeepsEverythingWithNoOwnAddressAndTheArrivalsComeBackInOrder() {
        exec(version15Chats)
        exec("INSERT INTO chats VALUES ('group_a', 1, 'G', 'aa,bb', 'a1', NULL, 100, 0, 'aa')")
        exec("INSERT INTO chats VALUES ('dm_b', 0, '', 'bb', NULL, NULL, 200, 1, NULL)")
        GroupsDistroMigration.STATEMENTS.forEach(::exec)
        db.createStatement().use { st ->
            st.executeQuery("SELECT id, isGroup, name, memberHashes, groupIdHex, createdAt, isArchived, creatorHex, ownMember FROM chats ORDER BY id").use { rs ->
                assertTrue(rs.next())
                assertEquals(listOf("dm_b", "0", "", "bb", null, "200", "1", null, null), (1..9).map { rs.getString(it) })
                assertTrue(rs.next())
                // Held at upgrade: no own address recorded, which reads as the device (§8.1).
                assertEquals(listOf("group_a", "1", "G", "aa,bb", "a1", "100", "0", "aa", null), (1..9).map { rs.getString(it) })
            }
        }
        exec("UPDATE chats SET ownMember = 'dd' WHERE id = 'group_a'")
        db.createStatement().use { st ->
            st.executeQuery("SELECT ownMember FROM chats WHERE id = 'group_a'").use { rs -> rs.next(); assertEquals("dd", rs.getString(1)) }
        }
        // Held arrivals keep their arrival order: the replay feeds them back in it (§6.5).
        db.prepareStatement("INSERT INTO group_arrivals (groupId, srcHex, msgHash, blobDigest, validated, distro, isPostSync, blob) VALUES (?, ?, ?, ?, ?, ?, ?, ?)").use {
            for (i in 0 until 3) {
                it.setString(1, "g$i"); it.setString(2, "s$i"); it.setString(3, "h$i"); it.setString(4, "d$i")
                it.setInt(5, i % 2); it.setString(6, "dd"); it.setInt(7, if (i == 2) 1 else 0); it.setBytes(8, byteArrayOf(i.toByte(), 9))
                it.executeUpdate()
            }
        }
        exec("DELETE FROM group_arrivals WHERE groupId = 'g1'")
        exec("INSERT INTO group_arrivals (groupId, srcHex, msgHash, blobDigest, validated, distro, isPostSync, blob) VALUES ('g3', 's3', 'h3', 'd3', 1, 'dd', 0, x'0303')")
        db.createStatement().use { st ->
            st.executeQuery("SELECT rowId, groupId, blob FROM group_arrivals ORDER BY rowId").use { rs ->
                val rows = buildList { while (rs.next()) add(Triple(rs.getLong(1), rs.getString(2), rs.getBytes(3))) }
                assertEquals("AUTOINCREMENT: an id is never used twice", listOf(1L, 3L, 4L), rows.map { it.first })
                assertEquals(listOf("g0", "g2", "g3"), rows.map { it.second })
                assertArrayEquals(byteArrayOf(3, 3), rows.last().third)
            }
        }
    }

    @Test
    fun theMigrationIsRegisteredAndEveryEarlierStepIsStillInTheChain() {
        val database = File("src/main/kotlin/com/newendian/retichat/data/db/RetichatDatabase.kt").readText()
        // 17 (reactions) came after it: ReactionsMigrationTest.
        assertFalse(database.contains("version = GroupsDistroMigration.TO,"))
        assertTrue(database.contains("MIGRATION_15_16"))
        for (step in listOf("MIGRATION_9_10", "MIGRATION_10_11", "MIGRATION_11_12", "MIGRATION_12_13", "MIGRATION_13_14", "MIGRATION_14_15", "MIGRATION_15_16")) {
            assertTrue(step, Regex("addMigrations\\([^)]*$step[^)]*\\)").containsMatchIn(database))
        }
        assertEquals(16, GroupsDistroMigration.TO)
        assertEquals(15, GroupsDistroMigration.FROM)
        assertEquals(DistroSyncMigration.TO, GroupsDistroMigration.FROM)
        assertTrue(database.contains("GroupArrivalEntity::class"))
        // The own address is a column of the chat, nullable, last.
        val entities = File("src/main/kotlin/com/newendian/retichat/data/db/entity/Entities.kt").readText()
        assertTrue(entities.contains("    val creatorHex: String? = null,\n    /**"))
        assertTrue(entities.contains("    val ownMember: String? = null,\n)"))
        assertNull(Regex("val ownMember: String\\s*[,)]").find(entities))
    }
}
