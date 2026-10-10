package com.newendian.retichat.data.db

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Database 14 → 15 on a real SQLite, the same statements the Room migration
 * runs: the distro sync proof's sealed bytes and signature on each owed entry
 * (RFed SPEC §17.13; DISTRO-SYNC-PROOF-DESIGN.md §7, §13.1 "The migration keeps
 * existing rows, with `sealed` null"). A missed migration would wipe users'
 * messages (`fallbackToDestructiveMigration`), so the chain is pinned too.
 */
class DistroSyncMigrationTest {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @After fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    /** Version 14's distro_outbox, as Room generated it (DistroChannelsMigration made it at 13; 14 left it alone). */
    private val version14Outbox =
        "CREATE TABLE `distro_outbox` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `distro` TEXT NOT NULL, `distroKey` TEXT NOT NULL, `msgHash` TEXT NOT NULL, `packed` BLOB NOT NULL, `label` TEXT NOT NULL, `op` TEXT, `name` TEXT, `atMs` INTEGER, `toHex` TEXT, `seq` INTEGER NOT NULL, PRIMARY KEY(`id`))"

    /** Version 15's distro_outbox as Room generates it from the entity. */
    private val version15Outbox =
        "CREATE TABLE `distro_outbox` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `distro` TEXT NOT NULL, `distroKey` TEXT NOT NULL, `msgHash` TEXT NOT NULL, `packed` BLOB NOT NULL, `label` TEXT NOT NULL, `op` TEXT, `name` TEXT, `atMs` INTEGER, `toHex` TEXT, `seq` INTEGER NOT NULL, `sealed` BLOB, `syncSig` BLOB, PRIMARY KEY(`id`))"

    /** name, type, notnull, default, pk of each column of [table], in order. */
    private fun info(conn: Connection, table: String): List<String> = conn.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            buildList {
                while (rs.next()) add("${rs.getString("name")}:${rs.getString("type")}:${rs.getInt("notnull")}:${rs.getString("dflt_value")}:${rs.getInt("pk")}")
            }
        }
    }

    private fun migrate() {
        exec(version14Outbox)
        DistroSyncMigration.STATEMENTS.forEach(::exec)
    }

    @Test
    fun theMigratedTableIsTheOneRoomExpects() {
        migrate()
        DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
            fresh.createStatement().use { it.execute(version15Outbox) }
            // ALTER TABLE adds the columns last, where the entity has them, so even the order matches.
            assertEquals(info(fresh, "distro_outbox"), info(db, "distro_outbox"))
        }
    }

    @Test
    fun theVersion15TableHereIsWhatRoomGenerates() {
        // exportSchema is off, so Room's generated createAllTables is the schema of record;
        // testDebugUnitTest runs after kspDebugKotlin.
        val impl = File("build/generated/ksp/debug/java/com/newendian/retichat/data/db/RetichatDatabase_Impl.java")
        Assume.assumeTrue(impl.exists())
        val room = Regex("CREATE TABLE IF NOT EXISTS `distro_outbox` \\([^\"]*").find(impl.readText())?.value
        assertEquals(version15Outbox, room?.replace("IF NOT EXISTS ", ""))
    }

    @Test
    fun theRowsAlreadyOwedKeepEverythingWithNoProofAndANewRowKeepsBoth() {
        exec(version14Outbox)
        db.prepareStatement("INSERT INTO distro_outbox VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)").use {
            for ((i, id) in listOf("channel:aa", "sent:bb").withIndex()) {
                it.setString(1, id); it.setString(2, if (i == 0) "channel" else "sent"); it.setString(3, "d".repeat(32))
                it.setString(4, "1".repeat(128)); it.setString(5, "hash$i"); it.setBytes(6, byteArrayOf(1, 2, i.toByte()))
                it.setString(7, "label $i"); it.setString(8, if (i == 0) "join" else null); it.setString(9, if (i == 0) "public.x" else null)
                if (i == 0) it.setLong(10, 1_767_225_600_000) else it.setNull(10, java.sql.Types.INTEGER)
                it.setString(11, if (i == 0) null else "c".repeat(32)); it.setLong(12, (i + 1).toLong())
                it.executeUpdate()
            }
        }
        DistroSyncMigration.STATEMENTS.forEach(::exec)
        db.createStatement().use { st ->
            st.executeQuery("SELECT id, kind, msgHash, packed, label, op, name, atMs, toHex, seq, sealed, syncSig FROM distro_outbox ORDER BY seq").use { rs ->
                assertTrue(rs.next())
                assertEquals(listOf("channel:aa", "channel", "hash0", "label 0", "join", "public.x", "1767225600000", null, "1"),
                    listOf(1, 2, 3, 5, 6, 7, 8, 9, 10).map { rs.getString(it) })
                assertArrayEquals(byteArrayOf(1, 2, 0), rs.getBytes(4))
                assertNull(rs.getBytes(11))
                assertNull(rs.getBytes(12))
                assertTrue(rs.next())
                assertEquals(listOf("sent:bb", "sent", "hash1", null, "c".repeat(32)), listOf(1, 2, 3, 6, 9).map { rs.getString(it) })
                assertNull(rs.getBytes(11))
                assertNull(rs.getBytes(12))
            }
        }
        // A message owed from now on keeps its sealed bytes and signature.
        db.prepareStatement("INSERT INTO distro_outbox (id, kind, distro, distroKey, msgHash, packed, label, seq, sealed, syncSig) VALUES ('sent:cc', 'sent', 'd', 'k', 'h', x'00', 'l', 3, ?, ?)").use {
            it.setBytes(1, ByteArray(300) { 9 }); it.setBytes(2, ByteArray(64) { 7 }); it.executeUpdate()
        }
        db.createStatement().use { st ->
            st.executeQuery("SELECT LENGTH(sealed), LENGTH(syncSig) FROM distro_outbox WHERE id = 'sent:cc'").use { rs ->
                assertTrue(rs.next())
                assertEquals(listOf(300, 64), listOf(rs.getInt(1), rs.getInt(2)))
            }
        }
    }

    @Test
    fun theMigrationIsRegisteredAndEveryEarlierStepIsStillInTheChain() {
        val database = File("src/main/kotlin/com/newendian/retichat/data/db/RetichatDatabase.kt").readText()
        assertTrue(database.contains("version = DistroSyncMigration.TO,"))
        assertTrue(database.contains("MIGRATION_14_15"))
        for (step in listOf("MIGRATION_9_10", "MIGRATION_10_11", "MIGRATION_11_12", "MIGRATION_12_13", "MIGRATION_13_14", "MIGRATION_14_15")) {
            assertTrue(step, Regex("addMigrations\\([^)]*$step[^)]*\\)").containsMatchIn(database))
        }
        // v0.1.10 shipped database 14 (GroupStatusesMigration): this is the step after it.
        assertEquals(15, DistroSyncMigration.TO)
        assertEquals(14, DistroSyncMigration.FROM)
        assertEquals(GroupStatusesMigration.TO, DistroSyncMigration.FROM)
        // Both columns nullable, last, in the entity.
        val entities = File("src/main/kotlin/com/newendian/retichat/data/db/entity/Entities.kt").readText()
        assertTrue(entities.contains("    val seq: Long,\n    val sealed: ByteArray? = null,\n    val syncSig: ByteArray? = null,\n)"))
    }
}
