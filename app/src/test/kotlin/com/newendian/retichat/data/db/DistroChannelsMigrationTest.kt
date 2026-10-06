package com.newendian.retichat.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Database 12 → 13 on a real SQLite, the same statements the Room migration
 * runs (RFed SPEC §17.12): the channel name state per posting identity, the
 * membership record and the distro outbox.
 */
class DistroChannelsMigrationTest {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    @After fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    /** Version 12's channel_name_state, as NamesMigration made it (Room 2.6). */
    private val version12NameState =
        "CREATE TABLE `channel_name_state` (`channelId` TEXT NOT NULL, `lastDigestHex` TEXT NOT NULL, `lastIncludedAt` INTEGER NOT NULL, PRIMARY KEY(`channelId`))"

    /** Version 13's tables as Room generates them from the entities. */
    private val version13 = mapOf(
        "channel_name_state" to "CREATE TABLE `channel_name_state` (`channelId` TEXT NOT NULL, `postingHex` TEXT NOT NULL, `lastDigestHex` TEXT NOT NULL, `lastIncludedAt` INTEGER NOT NULL, PRIMARY KEY(`channelId`, `postingHex`))",
        "channel_membership" to "CREATE TABLE `channel_membership` (`channelId` TEXT NOT NULL, `op` TEXT NOT NULL, `atMs` INTEGER NOT NULL, PRIMARY KEY(`channelId`))",
        "distro_outbox" to "CREATE TABLE `distro_outbox` (`id` TEXT NOT NULL, `kind` TEXT NOT NULL, `distro` TEXT NOT NULL, `distroKey` TEXT NOT NULL, `msgHash` TEXT NOT NULL, `packed` BLOB NOT NULL, `label` TEXT NOT NULL, `op` TEXT, `name` TEXT, `atMs` INTEGER, `toHex` TEXT, `seq` INTEGER NOT NULL, PRIMARY KEY(`id`))",
    )

    private val device = "0123456789abcdef0123456789abcdef"
    private val distro = "fedcba9876543210fedcba9876543210"

    private fun migrate() {
        exec(version12NameState)
        DistroChannelsMigration.STATEMENTS.forEach(::exec)
    }

    /** name, type, notnull, default, pk of each column of [table]. */
    private fun info(conn: Connection, table: String): List<String> = conn.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            buildList {
                while (rs.next()) add("${rs.getString("name")}:${rs.getString("type")}:${rs.getInt("notnull")}:${rs.getString("dflt_value")}:${rs.getInt("pk")}")
            }
        }
    }

    @Test
    fun theMigratedSchemaIsTheOneRoomExpects() {
        migrate()
        DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
            version13.values.forEach { sql -> fresh.createStatement().use { it.execute(sql) } }
            for (table in version13.keys) {
                assertEquals(table, info(fresh, table), info(db, table))
            }
        }
    }

    @Test
    fun theVersion13SchemaHereIsWhatRoomGenerates() {
        // exportSchema is off, so Room's generated createAllTables is the
        // schema of record; testDebugUnitTest runs after kspDebugKotlin.
        val impl = File("build/generated/ksp/debug/java/com/newendian/retichat/data/db/RetichatDatabase_Impl.java")
        Assume.assumeTrue(impl.exists())
        val text = impl.readText()
        for ((table, sql) in version13) {
            val room = Regex("CREATE TABLE IF NOT EXISTS `$table` \\([^\"]*").find(text)?.value
            assertEquals(table, sql, room?.replace("IF NOT EXISTS ", ""))
        }
    }

    @Test
    fun theMigrationCreatesTheTwoNewTablesEmptyAndNothingElseChanges() {
        migrate()
        for (table in listOf("channel_membership", "distro_outbox")) {
            db.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM `$table`").use { rs ->
                    rs.next()
                    assertEquals(table, 0, rs.getInt(1))
                }
            }
        }
        // The v12 shadow table is gone.
        db.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name = 'channel_name_state_v12'").use { rs ->
                rs.next()
                assertEquals(0, rs.getInt(1))
            }
        }
    }

    private data class State(val channel: String, val posting: String, val digest: String, val at: Long)

    private fun states(): List<State> = db.createStatement().use { st ->
        st.executeQuery("SELECT channelId, postingHex, lastDigestHex, lastIncludedAt FROM channel_name_state ORDER BY channelId, postingHex").use { rs ->
            buildList { while (rs.next()) add(State(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4))) }
        }
    }

    private fun run(sql: String, deviceHex: String): Int =
        db.prepareStatement(sql.replace(":deviceHex", "?")).use { st ->
            // The statement names :deviceHex once or twice; bind every one.
            val count = Regex(":deviceHex").findAll(sql).count()
            repeat(count) { st.setString(it + 1, deviceHex) }
            st.executeUpdate()
        }

    @Test
    fun theRowsAlreadyHereKeepTheirDigestAndTimeAndBecomeTheDevicesOnFirstLook() {
        exec(version12NameState)
        exec("INSERT INTO channel_name_state VALUES ('chA', 'aa', 1000)")
        exec("INSERT INTO channel_name_state VALUES ('chB', 'bb', 2000)")
        DistroChannelsMigration.STATEMENTS.forEach(::exec)
        // Nothing lost, none yet anyone's.
        assertEquals(listOf(State("chA", "", "aa", 1000), State("chB", "", "bb", 2000)), states())
        // The device looks for its state: the rows are its own from then on.
        assertEquals(2, run(NameStateSql.ADOPT_LEGACY, device))
        assertEquals(0, run(NameStateSql.DROP_LEGACY, device))
        assertEquals(listOf(State("chA", device, "aa", 1000), State("chB", device, "bb", 2000)), states())
        // A second look changes nothing.
        assertEquals(0, run(NameStateSql.ADOPT_LEGACY, device))
    }

    @Test
    fun aRowOfTheDevicesOwnIsNeverReplacedByALegacyOne() {
        migrate()
        exec("INSERT INTO channel_name_state VALUES ('chA', '', 'old', 1000)")
        exec("INSERT INTO channel_name_state VALUES ('chA', '$device', 'new', 5000)")
        exec("INSERT INTO channel_name_state VALUES ('chB', '', 'old-b', 1500)")
        assertEquals(1, run(NameStateSql.ADOPT_LEGACY, device))
        assertEquals(1, run(NameStateSql.DROP_LEGACY, device))
        assertEquals(listOf(State("chA", device, "new", 5000), State("chB", device, "old-b", 1500)), states())
    }

    @Test
    fun theDistrosStateIsKeptApartFromTheDevices() {
        migrate()
        exec("INSERT INTO channel_name_state VALUES ('chA', '$device', 'dev', 1000)")
        exec("INSERT INTO channel_name_state VALUES ('chA', '$distro', 'dis', 2000)")
        assertEquals(2, states().size)
        // A channel's rows all go with the channel.
        exec("DELETE FROM channel_name_state WHERE channelId = 'chA'")
        assertTrue(states().isEmpty())
    }

    @Test
    fun theMigrationIsRegistered() {
        val database = File("src/main/kotlin/com/newendian/retichat/data/db/RetichatDatabase.kt").readText()
        // Database 14 (GroupStatusesMigration) came after it: the version is its, the step stays.
        assertFalse(database.contains("version = DistroChannelsMigration.TO,"))
        assertEquals(DistroChannelsMigration.TO, GroupStatusesMigration.FROM)
        assertTrue(database.contains("MIGRATION_12_13"))
        // Every earlier step is still in the chain, so no version wipes the messages.
        for (step in listOf("MIGRATION_9_10", "MIGRATION_10_11", "MIGRATION_11_12", "MIGRATION_12_13")) {
            assertTrue(step, Regex("addMigrations\\([^)]*$step[^)]*\\)").containsMatchIn(database))
        }
        assertFalse(database.contains("version = ContactsMigration.TO,"))
        assertEquals(13, DistroChannelsMigration.TO)
        assertEquals(12, DistroChannelsMigration.FROM)
        assertEquals(ContactsMigration.TO, DistroChannelsMigration.FROM)
        for (entity in listOf("ChannelMembershipEntity::class", "DistroOutboxEntity::class")) {
            assertTrue(entity, database.contains(entity))
        }
    }
}
