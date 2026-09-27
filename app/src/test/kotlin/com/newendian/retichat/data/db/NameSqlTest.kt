package com.newendian.retichat.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * DISPLAY_NAMES.md §5.1 / §5.2: the name-slot writes the DAOs run
 * ([NameSql]), on a real SQLite with the version-11 tables.
 */
class NameSqlTest {
    private val db: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")
    private val hex = "0123456789abcdef0123456789abcdef"

    @Before fun create() {
        db.createStatement().use {
            it.execute("CREATE TABLE `contacts` (`destHashHex` TEXT NOT NULL, `localName` TEXT, `messageName` TEXT, `messageNameAt` REAL, `announceName` TEXT, `legacyName` TEXT, `publicKeyHex` TEXT, `addedAt` INTEGER NOT NULL, `isAllowlisted` INTEGER NOT NULL, PRIMARY KEY(`destHashHex`))")
            it.execute("CREATE TABLE `channel_senders` (`channelId` TEXT NOT NULL, `senderHex` TEXT NOT NULL, `channelName` TEXT, `firstSeenAt` INTEGER NOT NULL, `nameAt` INTEGER NOT NULL, PRIMARY KEY(`channelId`, `senderHex`))")
            it.execute("INSERT INTO contacts (destHashHex, legacyName, addedAt, isAllowlisted) VALUES ('$hex', 'Old', 1, 1)")
            it.execute("INSERT INTO channel_senders VALUES ('ch', '$hex', NULL, 1, 0)")
        }
    }

    @After fun close() = db.close()

    /** Run a Room query: each `:param` bound from [params] by name. */
    private fun run(sql: String, params: Map<String, Any?>): Int {
        val order = mutableListOf<String>()
        val jdbc = Regex(":([A-Za-z]+)").replace(sql) { m ->
            order.add(m.groupValues[1]); "?"
        }
        return db.prepareStatement(jdbc).use { st ->
            order.forEachIndexed { i, name ->
                require(name in params) { name }
                when (val v = params[name]) {
                    is Boolean -> st.setInt(i + 1, if (v) 1 else 0)
                    else -> st.setObject(i + 1, v)
                }
            }
            st.executeUpdate()
        }
    }

    private fun accept(name: String?, at: Double, onlyIfNone: Boolean = false) =
        run(NameSql.ACCEPT_MESSAGE_NAME, mapOf("hex" to hex, "name" to name, "at" to at, "onlyIfNone" to onlyIfNone))

    private fun announce(name: String?) = run(NameSql.SET_ANNOUNCE_NAME, mapOf("hex" to hex, "name" to name))

    private data class Slots(val message: String?, val at: Double?, val announce: String?, val legacy: String?)

    private fun slots(): Slots = db.createStatement().use { st ->
        st.executeQuery("SELECT messageName, messageNameAt, announceName, legacyName FROM contacts").use { rs ->
            rs.next()
            Slots(rs.getString(1), rs.getObject(2)?.let { (it as Number).toDouble() }, rs.getString(3), rs.getString(4))
        }
    }

    @Test
    fun anAcceptedNameRecordsItsTimeAndDropsTheLegacyName() {
        assertEquals(1, accept("Alice", 100.5))
        assertEquals(Slots("Alice", 100.5, null, null), slots())
    }

    @Test
    fun onlyANewerMessageLands() {
        accept("New", 200.0)
        // An older copy (propagated after the direct one) and an equal one.
        assertEquals(0, accept("Old", 150.0))
        assertEquals(0, accept(null, 200.0))
        assertEquals(Slots("New", 200.0, null, null), slots())
        // A repeat advances the time, so an older clear pulled late loses.
        assertEquals(1, accept("New", 300.0))
        assertEquals(0, accept(null, 250.0))
        assertEquals(Slots("New", 300.0, null, null), slots())
    }

    @Test
    fun sourceUnknownOnlyFillsAnEmptySlot() {
        assertEquals(1, accept("Alice", 10.0, onlyIfNone = true))
        assertEquals(0, accept("Mallory", 20.0, onlyIfNone = true))
        // Filled, but its unvouched time is not recorded.
        assertEquals(Slots("Alice", null, null, null), slots())
    }

    @Test
    fun aFarFutureSourceUnknownNameNeverBlocksTheRealSendersValidatedName() {
        // Someone claims Alice's hash before her key is known, with 0xD1
        // "Mallory" and a year-2096 timestamp.
        assertEquals(1, accept("Mallory", 4e9, onlyIfNone = true))
        assertEquals(Slots("Mallory", null, null, null), slots())
        // Alice's own validated name replaces it, and the order among
        // validated messages still holds.
        assertEquals(1, accept("Alice", 100.0))
        assertEquals(0, accept("Old", 90.0))
        assertEquals(Slots("Alice", 100.0, null, null), slots())
    }

    @Test
    fun aSourceUnknownNameAfterAValidatedClearKeepsTheClearsTime() {
        assertEquals(1, accept(null, 50.0))
        assertEquals(1, accept("Mallory", 4e9, onlyIfNone = true))
        assertEquals(Slots("Mallory", 50.0, null, null), slots())
        assertEquals(1, accept("Alice", 60.0))
        assertEquals(Slots("Alice", 60.0, null, null), slots())
    }

    @Test
    fun anAnnounceWithANameDropsTheLegacyNameAndANamelessOneKeepsIt() {
        announce(null)
        assertEquals(Slots(null, null, null, "Old"), slots())
        announce("Public")
        assertEquals(Slots(null, null, "Public", null), slots())
    }

    @Test
    fun channelNamesLandOnlyFromANewerPost() {
        fun set(name: String?, at: Long) = run(
            NameSql.SET_SENDER_CHANNEL_NAME,
            mapOf("channelId" to "ch", "senderHex" to hex, "name" to name, "postTimestampMs" to at),
        )
        assertEquals(1, set("Owl", 20))
        assertEquals(0, set(null, 10))
        assertEquals(0, set("Lark", 20))
        assertEquals(1, set("Owl", 30))
        db.createStatement().use { st ->
            st.executeQuery("SELECT channelName, nameAt FROM channel_senders").use { rs ->
                rs.next(); assertEquals("Owl", rs.getString(1)); assertEquals(30L, rs.getLong(2))
            }
        }
    }
}
