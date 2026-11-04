package com.newendian.retichat.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Database 17 → 18 on a real SQLite, the same statements the Room migration
 * runs: `channels` gains `lastActiveAt`, a channel's place in the chat list
 * (James, 2026-11-03), and every channel already here starts where it stands
 * now. A missed migration would wipe users' messages
 * (`fallbackToDestructiveMigration`), so the chain is pinned. And
 * `markChannelActive`, as the DAO's SQL runs it: it only moves a channel up.
 */
class ChannelActivityMigrationTest {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val channelDao = File("src/main/kotlin/com/newendian/retichat/data/db/dao/ChannelDao.kt").readText()

    @After fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    /** Version 17's channels, as Room generated it. */
    private val version17Channels =
        "CREATE TABLE `channels` (`id` TEXT NOT NULL, `channelName` TEXT NOT NULL, `rfedNodeIdentityHashHex` TEXT NOT NULL, `stampCost` INTEGER, `joinedAt` INTEGER NOT NULL, `lastMessageTime` INTEGER NOT NULL, `isArchived` INTEGER NOT NULL, PRIMARY KEY(`id`))"

    /** Version 18's channels as Room generates it from the entity. */
    private val version18Channels =
        "CREATE TABLE `channels` (`id` TEXT NOT NULL, `channelName` TEXT NOT NULL, `rfedNodeIdentityHashHex` TEXT NOT NULL, `stampCost` INTEGER, `joinedAt` INTEGER NOT NULL, `lastMessageTime` INTEGER NOT NULL, `isArchived` INTEGER NOT NULL, `lastActiveAt` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`id`))"

    private fun info(conn: Connection, table: String): List<String> = conn.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            buildList {
                while (rs.next()) add("${rs.getString("name")}:${rs.getString("type")}:${rs.getInt("notnull")}:${rs.getString("dflt_value")}:${rs.getInt("pk")}")
            }
        }
    }

    /** Joined at 1 with posts up to 50, joined at 2 with posts up to 30, joined at 70 with none yet. */
    private fun migrateThree() {
        exec(version17Channels)
        exec("INSERT INTO channels VALUES ('a', 'a', 'n', NULL, 1, 50, 0)")
        exec("INSERT INTO channels VALUES ('b', 'b', 'n', 5, 2, 30, 0)")
        exec("INSERT INTO channels VALUES ('c', 'c', 'n', NULL, 70, 0, 0)")
        ChannelActivityMigration.STATEMENTS.forEach(::exec)
    }

    @Test
    fun theMigratedTableIsTheOneRoomExpects() {
        exec(version17Channels)
        ChannelActivityMigration.STATEMENTS.forEach(::exec)
        DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
            fresh.createStatement().use { it.execute(version18Channels) }
            assertEquals(info(fresh, "channels"), info(db, "channels"))
        }
    }

    @Test
    fun theVersion18TableHereIsWhatRoomGenerates() {
        val impl = File("build/generated/ksp/debug/java/com/newendian/retichat/data/db/RetichatDatabase_Impl.java")
        Assume.assumeTrue(impl.exists())
        val room = Regex("CREATE TABLE IF NOT EXISTS `channels` \\([^\"]*").find(impl.readText())?.value
        assertEquals(version18Channels, room?.replace("IF NOT EXISTS ", ""))
    }

    @Test
    fun everyChannelStartsWhereItStandsNow() {
        migrateThree()
        assertEquals(listOf("a:50", "b:30", "c:0"), rows("SELECT id, lastActiveAt FROM channels ORDER BY id"))
        // Ordered as the chat list orders them now (lastActiveAt, else joinedAt),
        // which is how it ordered them before (lastMessageTime, else joinedAt).
        val now = "SELECT id FROM channels ORDER BY CASE WHEN lastActiveAt > 0 THEN lastActiveAt ELSE joinedAt END DESC"
        val before = "SELECT id FROM channels ORDER BY CASE WHEN lastMessageTime > 0 THEN lastMessageTime ELSE joinedAt END DESC"
        assertEquals(listOf("c", "a", "b"), rows(now))
        assertEquals(rows(before), rows(now))
        assertEquals("nothing else changed", listOf("a:a:n:null:1:50:0", "b:b:n:5:2:30:0", "c:c:n:null:70:0:0"),
            rows("SELECT id, channelName, rfedNodeIdentityHashHex, stampCost, joinedAt, lastMessageTime, isArchived FROM channels ORDER BY id"))
    }

    @Test
    fun markChannelActiveOnlyMovesAChannelUp() {
        migrateThree()
        val mark = query("markChannelActive")
        assertEquals(1, run(mark, "id" to "b", "at" to 100L))
        assertEquals(0, run(mark, "id" to "b", "at" to 90L))
        assertEquals(listOf("a:50", "b:100", "c:0"), rows("SELECT id, lastActiveAt FROM channels ORDER BY id"))
        // A newer post's time moves the shown time, not the place.
        exec(query("bumpLastMessageTime").replace(":ts", "200").replace(":id", "'a'"))
        assertEquals(listOf("a:200:50"), rows("SELECT id, lastMessageTime, lastActiveAt FROM channels WHERE id = 'a'"))
    }

    @Test
    fun theMigrationIsRegisteredAndEveryEarlierStepIsStillInTheChain() {
        val database = File("src/main/kotlin/com/newendian/retichat/data/db/RetichatDatabase.kt").readText()
        assertTrue(database.contains("version = ChannelActivityMigration.TO,"))
        for (step in listOf("MIGRATION_9_10", "MIGRATION_10_11", "MIGRATION_11_12", "MIGRATION_12_13", "MIGRATION_13_14",
                            "MIGRATION_14_15", "MIGRATION_15_16", "MIGRATION_16_17", "MIGRATION_17_18")) {
            assertTrue(step, Regex("addMigrations\\([^)]*$step[^)]*\\)").containsMatchIn(database))
        }
        assertEquals(18, ChannelActivityMigration.TO)
        assertEquals(ReactionsMigration.TO, ChannelActivityMigration.FROM)
        // The column is the entity's last, with the default the migration gives it.
        val entities = File("src/main/kotlin/com/newendian/retichat/data/db/entity/Entities.kt").readText()
        assertTrue(entities.contains("    @ColumnInfo(defaultValue = \"0\") val lastActiveAt: Long = 0L,\n)"))
    }

    // ---- helpers ----

    /** The @Query string right before `fun [name](` in the DAO. */
    private fun query(name: String): String {
        val at = channelDao.indexOf("fun $name(")
        assertTrue("fun $name in the DAO", at >= 0)
        val q = Regex("@Query\\(\"((?:[^\"\\\\]|\\\\.)*)\"\\)").findAll(channelDao.substring(0, at)).lastOrNull()
        assertNotNull("the @Query of $name", q)
        return q!!.groupValues[1]
    }

    /** Runs [sql] with its `:name` parameters bound from [params]; the rows it changed. */
    private fun run(sql: String, vararg params: Pair<String, Any>): Int {
        val values = mutableListOf<Any>()
        val jdbc = Regex(":([A-Za-z]+)").replace(sql) { m -> values.add(params.toMap().getValue(m.groupValues[1])); "?" }
        return db.prepareStatement(jdbc).use { st ->
            values.forEachIndexed { i, v -> st.setObject(i + 1, v) }
            st.executeUpdate()
        }
    }

    /** Each row of [sql], its columns joined by ':'. */
    private fun rows(sql: String): List<String> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val n = rs.metaData.columnCount
            buildList { while (rs.next()) add((1..n).joinToString(":") { rs.getString(it) ?: "null" }) }
        }
    }
}
