package com.newendian.retichat.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Database 16 → 17 on a real SQLite, the same statements the Room migration
 * runs (REACTIONS-DESIGN.md §6): `messages` gains `lxmfHash`, `postId` and
 * `reactionsJson`, null for every row already here. A missed migration would
 * wipe users' messages (`fallbackToDestructiveMigration`), so the chain is
 * pinned. And `find_target` (§3), as the DAO's SQL runs it: never outside the
 * chat the reaction came in on.
 */
class ReactionsMigrationTest {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val messageDao = File("src/main/kotlin/com/newendian/retichat/data/db/dao/MessageDao.kt").readText()

    @After fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    /** Version 16's messages, as Room generated it (NamesMigration added systemKind at 11). */
    private val version16Messages =
        "CREATE TABLE `messages` (`id` TEXT NOT NULL, `chatId` TEXT NOT NULL, `senderHashHex` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `isOutbound` INTEGER NOT NULL, `state` INTEGER NOT NULL, `nativeHandle` INTEGER NOT NULL, `progress` REAL NOT NULL, `systemKind` TEXT, PRIMARY KEY(`id`))"

    /** Version 17's messages as Room generates it from the entity. */
    private val version17Messages =
        "CREATE TABLE `messages` (`id` TEXT NOT NULL, `chatId` TEXT NOT NULL, `senderHashHex` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `isOutbound` INTEGER NOT NULL, `state` INTEGER NOT NULL, `nativeHandle` INTEGER NOT NULL, `progress` REAL NOT NULL, `systemKind` TEXT, `lxmfHash` TEXT, `postId` TEXT, `reactionsJson` TEXT, PRIMARY KEY(`id`))"

    private fun info(conn: Connection, table: String): List<String> = conn.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            buildList {
                while (rs.next()) add("${rs.getString("name")}:${rs.getString("type")}:${rs.getInt("notnull")}:${rs.getString("dflt_value")}:${rs.getInt("pk")}")
            }
        }
    }

    private fun migrate() {
        exec(version16Messages)
        ReactionsMigration.STATEMENTS.forEach(::exec)
    }

    @Test
    fun theMigratedTableIsTheOneRoomExpects() {
        migrate()
        DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
            fresh.createStatement().use { it.execute(version17Messages) }
            assertEquals(info(fresh, "messages"), info(db, "messages"))
        }
    }

    @Test
    fun theVersion17TableHereIsWhatRoomGenerates() {
        val impl = File("build/generated/ksp/debug/java/com/newendian/retichat/data/db/RetichatDatabase_Impl.java")
        Assume.assumeTrue(impl.exists())
        val room = Regex("CREATE TABLE IF NOT EXISTS `messages` \\([^\"]*").find(impl.readText())?.value
        assertEquals(version17Messages, room?.replace("IF NOT EXISTS ", ""))
    }

    @Test
    fun rowsHeldBeforeKeepEverythingWithNoReactions() {
        exec(version16Messages)
        exec("INSERT INTO messages VALUES ('${"a".repeat(64)}', 'dm_x', 'x', 'hi', 5, 0, 8, 0, 0.0, NULL)")
        ReactionsMigration.STATEMENTS.forEach(::exec)
        db.createStatement().use { st ->
            st.executeQuery("SELECT content, systemKind, lxmfHash, postId, reactionsJson FROM messages").use { rs ->
                assertTrue(rs.next())
                assertEquals(listOf("hi", null, null, null, null), (1..5).map { rs.getString(it) })
            }
        }
    }

    @Test
    fun findTargetStaysInsideTheChat() {
        migrate()
        val h = "ab".repeat(32)
        val other = "cd".repeat(32)
        val post = "0123456789abcdef0123456789abcdef"
        // A received DM whose id is its hash, an outgoing one whose hash is kept beside its local id,
        // the same hash in another chat, and a Group post with its id.
        exec("INSERT INTO messages VALUES ('$h', 'dm_a', 'a', 'in', 1, 0, 8, 0, 0.0, NULL, NULL, NULL, NULL)")
        exec("INSERT INTO messages VALUES ('out_2', 'dm_a', 'me', 'out', 2, 1, 4, 0, 0.0, NULL, '$other', NULL, NULL)")
        exec("INSERT INTO messages VALUES ('out_3', 'dm_b', 'me', 'elsewhere', 3, 1, 4, 0, 0.0, NULL, '$h', NULL, NULL)")
        exec("INSERT INTO messages VALUES ('grp_4', 'group_g', 'me', 'post', 4, 1, 4, 0, 0.0, NULL, NULL, '$post', NULL)")

        val byHash = query("findByLxmfHash")
        val byPost = query("findByPostId")
        assertEquals("the id is the hash", h, one(byHash, "chatId" to "dm_a", "hash" to h))
        assertEquals("the kept hash of an outgoing message", "out_2", one(byHash, "chatId" to "dm_a", "hash" to other))
        assertEquals("the same hash in another chat is that chat's", "out_3", one(byHash, "chatId" to "dm_b", "hash" to h))
        assertNull("never outside the chat", one(byHash, "chatId" to "dm_c", "hash" to h))
        assertEquals("grp_4", one(byPost, "chatId" to "group_g", "postId" to post))
        assertNull("never outside the chat", one(byPost, "chatId" to "group_h", "postId" to post))

        // Writing reactions keeps the row's rowid: the chat list orders by MAX(rowid).
        val before = text("SELECT rowid FROM messages WHERE id = 'out_2'")
        val write = query("setReactionsJson").replace(":json", "'{}'").replace(":id", "'out_2'")
        exec(write)
        assertEquals(before, text("SELECT rowid FROM messages WHERE id = 'out_2'"))
        assertEquals("{}", text("SELECT reactionsJson FROM messages WHERE id = 'out_2'"))
    }

    @Test
    fun theMigrationIsRegisteredAndEveryEarlierStepIsStillInTheChain() {
        val database = File("src/main/kotlin/com/newendian/retichat/data/db/RetichatDatabase.kt").readText()
        assertTrue(database.contains("version = ReactionsMigration.TO,"))
        for (step in listOf("MIGRATION_9_10", "MIGRATION_10_11", "MIGRATION_11_12", "MIGRATION_12_13", "MIGRATION_13_14",
                            "MIGRATION_14_15", "MIGRATION_15_16", "MIGRATION_16_17")) {
            assertTrue(step, Regex("addMigrations\\([^)]*$step[^)]*\\)").containsMatchIn(database))
        }
        assertEquals(17, ReactionsMigration.TO)
        assertEquals(GroupsDistroMigration.TO, ReactionsMigration.FROM)
        // The three columns are nullable, last.
        val entities = File("src/main/kotlin/com/newendian/retichat/data/db/entity/Entities.kt").readText()
        assertTrue(entities.contains("    val reactionsJson: String? = null,\n)"))
        for (column in listOf("lxmfHash", "postId", "reactionsJson")) {
            assertNull(column, Regex("val $column: String\\s*[,)]").find(entities))
        }
    }

    // ---- helpers ----

    /** The @Query string right before `fun [name](` in the DAO. */
    private fun query(name: String): String {
        val at = messageDao.indexOf("fun $name(")
        assertTrue("fun $name in the DAO", at >= 0)
        val q = Regex("@Query\\(\"((?:[^\"\\\\]|\\\\.)*)\"\\)").findAll(messageDao.substring(0, at)).lastOrNull()
        assertNotNull("the @Query of $name", q)
        return q!!.groupValues[1]
    }

    /** The id of the one row [sql] selects with [params], or null. */
    private fun one(sql: String, vararg params: Pair<String, String>): String? {
        val values = mutableListOf<String>()
        val jdbc = Regex(":([A-Za-z]+)").replace(sql) { m ->
            values.add(params.toMap().getValue(m.groupValues[1])); "?"
        }
        return db.prepareStatement(jdbc).use { st ->
            values.forEachIndexed { i, v -> st.setString(i + 1, v) }
            st.executeQuery().use { rs -> if (rs.next()) rs.getString("id") else null }
        }
    }

    private fun text(sql: String): String = db.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getString(1) } }
}
