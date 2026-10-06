package com.newendian.retichat.data.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/**
 * Database 11 → 12 on a real SQLite, the same statements the Room migration
 * runs: explicit contacts (James, 2026-10-02) and the DISPLAY_NAMES.md §5.4
 * pass over names migrated before the old web node defaults and the old web
 * announce suffix were placeholders.
 */
class ContactsMigrationTest {
    private val db = DriverManager.getConnection("jdbc:sqlite::memory:")

    @After fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    /** Version 11's contacts, as Room 2.6 generated it. */
    private val version11 =
        "CREATE TABLE `contacts` (`destHashHex` TEXT NOT NULL, `localName` TEXT, `messageName` TEXT, `messageNameAt` REAL, `announceName` TEXT, `legacyName` TEXT, `publicKeyHex` TEXT, `addedAt` INTEGER NOT NULL, `isAllowlisted` INTEGER NOT NULL, PRIMARY KEY(`destHashHex`))"

    /** Version 12's contacts as Room generates it from ContactEntity. */
    private val version12 =
        "CREATE TABLE `contacts` (`destHashHex` TEXT NOT NULL, `localName` TEXT, `messageName` TEXT, `messageNameAt` REAL, `announceName` TEXT, `legacyName` TEXT, `publicKeyHex` TEXT, `addedAt` INTEGER NOT NULL, `isAllowlisted` INTEGER NOT NULL, `isContact` INTEGER NOT NULL DEFAULT 1, PRIMARY KEY(`destHashHex`))"

    private fun add(hash: String, local: String? = null, legacy: String? = null, announce: String? = null, allowlisted: Boolean = true) =
        db.prepareStatement("INSERT INTO contacts (destHashHex, localName, announceName, legacyName, addedAt, isAllowlisted) VALUES (?, ?, ?, ?, 7, ?)").use {
            it.setString(1, hash); it.setString(2, local); it.setString(3, announce); it.setString(4, legacy)
            it.setInt(5, if (allowlisted) 1 else 0)
            it.executeUpdate()
        }

    private data class Names(val local: String?, val legacy: String?, val announce: String?)

    private fun names(hash: String): Names = db.prepareStatement(
        "SELECT localName, legacyName, announceName FROM contacts WHERE destHashHex = ?"
    ).use { st ->
        st.setString(1, hash)
        st.executeQuery().use { rs -> rs.next(); Names(rs.getString(1), rs.getString(2), rs.getString(3)) }
    }

    private fun listed(): Set<String> = db.createStatement().use { st ->
        st.executeQuery(ContactSql.LISTED).use { rs ->
            buildSet { while (rs.next()) add(rs.getString("destHashHex")) }
        }
    }

    private fun migrate() {
        exec(version11)
        ContactsMigration.STATEMENTS.forEach(::exec)
    }

    @Test
    fun theRowsAlreadyHereStayContactsAndHiddenRowsAreListedNowhere() {
        migrate()
        val kept = "0123456789abcdef0123456789abcdef"
        add(kept, allowlisted = false)
        assertEquals(setOf(kept), listed())
        // A row the app makes for a group member or a stranger's DM is hidden.
        val member = "fedcba9876543210fedcba9876543210"
        exec("INSERT INTO contacts (destHashHex, addedAt, isAllowlisted, isContact) VALUES ('$member', 7, 1, 0)")
        assertEquals(setOf(kept), listed())
        // The user adds it: a contact from then on.
        db.prepareStatement(ContactSql.SET_CONTACT.replace(":hex", "?")).use { it.setString(1, member); it.executeUpdate() }
        assertEquals(setOf(kept, member), listed())
    }

    @Test
    fun theMigratedSchemaIsTheOneRoomExpects() {
        migrate()
        val fresh = DriverManager.getConnection("jdbc:sqlite::memory:")
        fresh.use { f ->
            f.createStatement().use { it.execute(version12) }
            assertEquals(info(f), info(db))
        }
    }

    /** name, type, notnull, default, pk of each column of contacts. */
    private fun info(conn: java.sql.Connection): List<String> = conn.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`contacts`)").use { rs ->
            buildList {
                while (rs.next()) add("${rs.getString("name")}:${rs.getString("type")}:${rs.getInt("notnull")}:${rs.getString("dflt_value")}:${rs.getInt("pk")}")
            }
        }
    }

    @Test
    fun theVersion12SchemaHereIsWhatRoomGenerates() {
        // exportSchema is off, so Room's generated createAllTables is the
        // schema of record; testDebugUnitTest runs after kspDebugKotlin.
        val impl = File("build/generated/ksp/debug/java/com/newendian/retichat/data/db/RetichatDatabase_Impl.java")
        Assume.assumeTrue(impl.exists())
        val room = Regex("CREATE TABLE IF NOT EXISTS `contacts` \\([^\"]*").find(impl.readText())?.value
        assertEquals(version12, room?.replace("IF NOT EXISTS ", ""))
    }

    @Test
    fun oldWebNodeDefaultsAndTheOwnHashSuffixLeaveTheStoredNames() {
        exec(version11)
        val a = "0123456789abcdef0123456789abcdef"
        val b = "fedcba9876543210fedcba9876543210"
        val c = "aaaaaaaabbbbbbbbccccccccdddddddd"
        val d = "11111111222222223333333344444444"
        // §5.4: a localName or legacyName that is an old web node default goes.
        add(a, local = "Retichat Web (retichat)", legacy = " Retichat Web (selectiv)\n", announce = "Retichat Web (retichat)")
        // The suffix with the row's own hash is stripped from all three slots.
        add(b, local = "Alice (fedcba987654)", legacy = "Bob (FEDCBA987654) ", announce = "Carol\u2003 (fedcba987654)")
        // What is left came from an announce: a placeholder is dropped, even as a localName.
        add(c, local = "Retichat (aaaaaaaabbbb)", legacy = "aaaaaaaa (aaaaaaaabbbb)", announce = "Retichat Web (x) (aaaaaaaabbbb)")
        // Another identity's hex, a typed hex name and parentheses stay.
        add(d, local = "Dan (fedcba987654)", legacy = "deadbeef", announce = "Bob (work)")
        ContactsMigration.STATEMENTS.forEach(::exec)
        assertEquals(Names(null, null, "Retichat Web (retichat)"), names(a))
        assertEquals(Names("Alice", "Bob", "Carol"), names(b))
        assertEquals(Names(null, null, null), names(c))
        assertEquals(Names("Dan (fedcba987654)", "deadbeef", "Bob (work)"), names(d))
        // Every row that was here stays a contact.
        assertEquals(setOf(a, b, c, d), listed())
        // A second run of the pass changes nothing more.
        ContactsMigration.STATEMENTS.drop(1).forEach(::exec)
        assertEquals(Names("Alice", "Bob", "Carol"), names(b))
    }

    @Test
    fun theMigrationIsRegistered() {
        val database = File("src/main/kotlin/com/newendian/retichat/data/db/RetichatDatabase.kt").readText()
        // Database 13 followed it (DistroChannelsMigrationTest pins that one):
        // this step stays in the chain, and the version is no longer 12.
        assertTrue(database.contains("MIGRATION_11_12"))
        assertTrue(Regex("addMigrations\\([^)]*MIGRATION_11_12[^)]*\\)").containsMatchIn(database))
        assertFalse(database.contains("version = ContactsMigration.TO,"))
        assertFalse(database.contains("version = NamesMigration.TO,"))
        assertEquals(12, ContactsMigration.TO)
    }
}
