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
 * Database 18 → 19 on a real SQLite, the same statement the Room migration
 * runs: the sender's stamp on a message received direct (RFed SPEC §17.15).
 * A missed migration would wipe users' messages (`fallbackToDestructiveMigration`),
 * so the chain is pinned too.
 */
class DirectUploadMigrationTest {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @After fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    /** Version 18's distro_outbox: version 15's (DistroSyncMigration), which 16 to 18 left alone. */
    private val version18Outbox =
        "CREATE TABLE `distro_outbox` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `distro` TEXT NOT NULL, `distroKey` TEXT NOT NULL, `msgHash` TEXT NOT NULL, `packed` BLOB NOT NULL, `label` TEXT NOT NULL, `op` TEXT, `name` TEXT, `atMs` INTEGER, `toHex` TEXT, `seq` INTEGER NOT NULL, `sealed` BLOB, `syncSig` BLOB, PRIMARY KEY(`id`))"

    /** Version 19's distro_outbox as Room generates it from the entity. */
    private val version19Outbox =
        "CREATE TABLE `distro_outbox` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `distro` TEXT NOT NULL, `distroKey` TEXT NOT NULL, `msgHash` TEXT NOT NULL, `packed` BLOB NOT NULL, `label` TEXT NOT NULL, `op` TEXT, `name` TEXT, `atMs` INTEGER, `toHex` TEXT, `seq` INTEGER NOT NULL, `sealed` BLOB, `syncSig` BLOB, `stamp` BLOB, PRIMARY KEY(`id`))"

    /** name, type, notnull, default, pk of each column of [table], in order. */
    private fun info(conn: Connection, table: String): List<String> = conn.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            buildList {
                while (rs.next()) add("${rs.getString("name")}:${rs.getString("type")}:${rs.getInt("notnull")}:${rs.getString("dflt_value")}:${rs.getInt("pk")}")
            }
        }
    }

    @Test
    fun theMigratedTableIsTheOneRoomExpects() {
        exec(version18Outbox)
        DirectUploadMigration.STATEMENTS.forEach(::exec)
        DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
            fresh.createStatement().use { it.execute(version19Outbox) }
            // ALTER TABLE adds the column last, where the entity has it, so even the order matches.
            assertEquals(info(fresh, "distro_outbox"), info(db, "distro_outbox"))
        }
    }

    @Test
    fun theVersion19TableHereIsWhatRoomGenerates() {
        // exportSchema is off, so Room's generated createAllTables is the schema of record;
        // testDebugUnitTest runs after kspDebugKotlin.
        val impl = File("build/generated/ksp/debug/java/com/newendian/retichat/data/db/RetichatDatabase_Impl.java")
        Assume.assumeTrue(impl.exists())
        val room = Regex("CREATE TABLE IF NOT EXISTS `distro_outbox` \\([^\"]*").find(impl.readText())?.value
        assertEquals(version19Outbox, room?.replace("IF NOT EXISTS ", ""))
    }

    @Test
    fun theRowsAlreadyOwedKeepEverythingWithNoStampAndAMessageReceivedDirectKeepsIts() {
        exec(version18Outbox)
        exec("INSERT INTO distro_outbox (id, kind, distro, distroKey, msgHash, packed, label, toHex, seq, sealed, syncSig) " +
            "VALUES ('sent:bb', 'sent', 'd', 'k', 'hash1', x'010203', 'a sent-copy', 'c', 1, x'0909', x'0707')")
        DirectUploadMigration.STATEMENTS.forEach(::exec)
        db.createStatement().use { st ->
            st.executeQuery("SELECT id, kind, msgHash, packed, label, toHex, seq, sealed, syncSig, stamp FROM distro_outbox").use { rs ->
                assertTrue(rs.next())
                assertEquals(listOf("sent:bb", "sent", "hash1", "a sent-copy", "c", "1"), listOf(1, 2, 3, 5, 6, 7).map { rs.getString(it) })
                assertArrayEquals(byteArrayOf(1, 2, 3), rs.getBytes(4))
                assertArrayEquals(byteArrayOf(9, 9), rs.getBytes(8))
                assertArrayEquals(byteArrayOf(7, 7), rs.getBytes(9))
                assertNull(rs.getBytes(10))
            }
        }
        // A message received direct keeps the sender's stamp beside its sealed bytes and signature.
        db.prepareStatement("INSERT INTO distro_outbox (id, kind, distro, distroKey, msgHash, packed, label, seq, sealed, syncSig, stamp) " +
            "VALUES ('direct:cc', 'direct', 'd', 'k', 'cc', x'', 'received direct', 2, ?, ?, ?)").use {
            it.setBytes(1, ByteArray(300) { 9 }); it.setBytes(2, ByteArray(64) { 7 }); it.setBytes(3, ByteArray(32) { 5 }); it.executeUpdate()
        }
        db.createStatement().use { st ->
            st.executeQuery("SELECT LENGTH(packed), LENGTH(sealed), LENGTH(syncSig), LENGTH(stamp) FROM distro_outbox WHERE id = 'direct:cc'").use { rs ->
                assertTrue(rs.next())
                assertEquals(listOf(0, 300, 64, 32), listOf(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)))
            }
        }
    }

    @Test
    fun theMigrationIsRegisteredAndEveryEarlierStepIsStillInTheChain() {
        val database = File("src/main/kotlin/com/newendian/retichat/data/db/RetichatDatabase.kt").readText()
        assertTrue(database.contains("version = DirectUploadMigration.TO,"))
        for (step in listOf("MIGRATION_9_10", "MIGRATION_10_11", "MIGRATION_11_12", "MIGRATION_12_13", "MIGRATION_13_14",
                            "MIGRATION_14_15", "MIGRATION_15_16", "MIGRATION_16_17", "MIGRATION_17_18", "MIGRATION_18_19")) {
            assertTrue(step, Regex("addMigrations\\([^)]*$step[^)]*\\)").containsMatchIn(database))
        }
        assertEquals(19, DirectUploadMigration.TO)
        assertEquals(ChannelActivityMigration.TO, DirectUploadMigration.FROM)
        // The column is nullable and the entity's last.
        val entities = File("src/main/kotlin/com/newendian/retichat/data/db/entity/Entities.kt").readText()
        assertTrue(entities.contains("    val syncSig: ByteArray? = null,\n    val stamp: ByteArray? = null,\n)"))
    }
}
