package com.newendian.retichat.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The queues a post waits in while the app is starting (James's UI rulings,
 * 2026-10-11; GROUPS-DISTRO-DESIGN.md §8.7), run on a real SQLite with the
 * tables Room declares: a group post in the DM queue (`messages`, OUTBOUND, no
 * native handle) and a channel post (`channel_messages`, sendState 3). Each is
 * listed until it is taken, taken by one caller only (a second release finds it
 * gone, so it is never sent twice), and kept by the next start's recovery of
 * what was in flight when the process died: it waits for the start as a DM does.
 * The SQL is read from the DAO sources, where Room reads it.
 */
class QueuedPostsSqlTest {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val messageDao = File("src/main/kotlin/com/newendian/retichat/data/db/dao/MessageDao.kt").readText()
    private val channelDao = File("src/main/kotlin/com/newendian/retichat/data/db/dao/ChannelDao.kt").readText()

    // As RetichatDatabase_Impl's createAllTables makes them (exportSchema is off; checked below).
    private val messages = "CREATE TABLE `messages` (`id` TEXT NOT NULL, `chatId` TEXT NOT NULL, `senderHashHex` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `isOutbound` INTEGER NOT NULL, `state` INTEGER NOT NULL, `nativeHandle` INTEGER NOT NULL, `progress` REAL NOT NULL, `systemKind` TEXT, PRIMARY KEY(`id`))"
    private val channelMessages = "CREATE TABLE `channel_messages` (`id` TEXT NOT NULL, `channelId` TEXT NOT NULL, `sourceHashHex` TEXT NOT NULL, `title` TEXT NOT NULL, `content` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `isOutbound` INTEGER NOT NULL, `signatureValidated` INTEGER NOT NULL, `sendState` INTEGER NOT NULL, PRIMARY KEY(`id`))"

    private val outbound = 0x01
    private val sending = 0x02
    private val failed = 0xFF
    private val queued = 3
    private val posting = "d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0d0"

    @Before fun create() {
        db.createStatement().use {
            it.execute(messages)
            it.execute(channelMessages)
            // A group post held at start-up, a DM in flight when the process died, and one sent.
            it.execute("INSERT INTO messages VALUES ('grp_1', 'group_1', '', 'held', 1, 1, $outbound, 0, 0, NULL)")
            it.execute("INSERT INTO messages VALUES ('out_2', 'dm_2', '', 'in flight', 2, 1, $sending, 0, 0, NULL)")
            it.execute("INSERT INTO messages VALUES ('out_3', 'dm_3', '', 'sent', 3, 1, 0x03, 7, 0, NULL)")
            // A channel post queued at start-up, one in flight when the process died, one sent.
            it.execute("INSERT INTO channel_messages VALUES ('pending|1', 'ch', '', '', 'queued', 1, 1, 1, $queued)")
            it.execute("INSERT INTO channel_messages VALUES ('pending|2', 'ch', '', '', 'in flight', 2, 1, 1, 0)")
            it.execute("INSERT INTO channel_messages VALUES ('x|3', 'ch', 'aa', '', 'sent', 3, 1, 1, 1)")
        }
    }

    @After fun close() = db.close()

    @Test
    fun theTablesHereAreWhatRoomGenerates() {
        val impl = File("build/generated/ksp/debug/java/com/newendian/retichat/data/db/RetichatDatabase_Impl.java")
        org.junit.Assume.assumeTrue(impl.exists())
        val text = impl.readText()
        for (table in listOf(messages, channelMessages)) {
            val name = Regex("CREATE TABLE (`\\w+`)").find(table)!!.groupValues[1]
            assertEquals(table, Regex("CREATE TABLE IF NOT EXISTS $name \\([^\"]*").find(text)?.value?.replace("IF NOT EXISTS ", ""))
        }
    }

    @Test
    fun aGroupPostHeldAtStartUpIsInTheDmQueueTakenOnceAndKeptByTheNextStartsRecovery() {
        exec(query(messageDao, "failStaleOutbound"), emptyMap())
        assertEquals("waits, as a queued DM does", outbound, state("grp_1"))
        assertEquals("what was in flight is failed", failed, state("out_2"))
        assertEquals(listOf("grp_1"), select(query(messageDao, "pendingOutbound"), emptyMap()))

        val claim = query(messageDao, "claimQueuedGroupPost")
        assertEquals(1, exec(claim, mapOf("id" to "grp_1", "state" to sending, "sender" to posting)))
        assertEquals("a second release finds it gone", 0, exec(claim, mapOf("id" to "grp_1", "state" to sending, "sender" to posting)))
        assertEquals(sending, state("grp_1"))
        assertEquals("sent as the own address resolved then", posting, text("SELECT senderHashHex FROM messages WHERE id = 'grp_1'"))
        assertEquals(emptyList<String>(), select(query(messageDao, "pendingOutbound"), emptyMap()))
        // Nothing but a queued row is taken.
        assertEquals(0, exec(claim, mapOf("id" to "out_3", "state" to failed, "sender" to posting)))
        assertEquals(0x03, state("out_3"))
    }

    @Test
    fun aGroupPostThatCannotBeSignedWhenTheStartFinishesIsTakenAsFailed() {
        assertEquals(1, exec(query(messageDao, "claimQueuedGroupPost"), mapOf("id" to "grp_1", "state" to failed, "sender" to "")))
        assertEquals(failed, state("grp_1"))
        assertEquals(emptyList<String>(), select(query(messageDao, "pendingOutbound"), emptyMap()))
    }

    @Test
    fun aChannelPostQueuedAtStartUpIsListedTakenOnceAndKeptByTheNextStartsRecovery() {
        assertEquals("only what was in flight", 1, exec(query(channelDao, "failStaleOutboundSendingMessages"), emptyMap()))
        assertEquals(queued, sendState("pending|1"))
        assertEquals(2, sendState("pending|2"))
        val list = query(channelDao, "queuedOutboundMessages")
        assertEquals(listOf("pending|1"), select(list, emptyMap()))

        val claim = query(channelDao, "claimQueuedMessage")
        assertEquals(1, exec(claim, mapOf("id" to "pending|1", "postingHex" to posting)))
        assertEquals("a second release finds it gone", 0, exec(claim, mapOf("id" to "pending|1", "postingHex" to posting)))
        assertEquals("sending now", 0, sendState("pending|1"))
        assertEquals("filed under the posting identity held then", posting, text("SELECT sourceHashHex FROM channel_messages WHERE id = 'pending|1'"))
        assertEquals(emptyList<String>(), select(list, emptyMap()))
        assertEquals("nothing but a queued post is taken", 0, exec(claim, mapOf("id" to "x|3", "postingHex" to posting)))
    }

    // ---- helpers ----

    /** The @Query string right before `fun [name](` in [dao]. */
    private fun query(dao: String, name: String): String {
        val at = dao.indexOf("fun $name(")
        assertTrue("fun $name in the DAO", at >= 0)
        val q = Regex("@Query\\(\"((?:[^\"\\\\]|\\\\.)*)\"\\)").findAll(dao.substring(0, at)).lastOrNull()
        assertNotNull("the @Query of $name", q)
        return q!!.groupValues[1]
    }

    private fun bind(sql: String, params: Map<String, Any?>): Pair<String, List<Any?>> {
        val values = mutableListOf<Any?>()
        val jdbc = Regex(":([A-Za-z]+)").replace(sql) { m ->
            val n = m.groupValues[1]
            require(n in params) { n }
            values.add(params[n]); "?"
        }
        return jdbc to values
    }

    private fun exec(sql: String, params: Map<String, Any?>): Int {
        val (jdbc, values) = bind(sql, params)
        return db.prepareStatement(jdbc).use { st ->
            values.forEachIndexed { i, v -> st.setObject(i + 1, v) }
            st.executeUpdate()
        }
    }

    private fun select(sql: String, params: Map<String, Any?>): List<String> {
        val (jdbc, values) = bind(sql, params)
        return db.prepareStatement(jdbc).use { st ->
            values.forEachIndexed { i, v -> st.setObject(i + 1, v) }
            st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }
    }

    private fun text(sql: String): String = db.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getString(1) } }
    private fun state(id: String): Int = text("SELECT state FROM messages WHERE id = '$id'").toInt()
    private fun sendState(id: String): Int = text("SELECT sendState FROM channel_messages WHERE id = '$id'").toInt()
}
