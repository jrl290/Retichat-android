package com.newendian.retichat.names

import com.newendian.retichat.bridge.MiniJson
import com.newendian.retichat.data.db.NamesMigration
import com.newendian.retichat.service.UserPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/**
 * DISPLAY_NAMES.md §5.4 (and §3's white space), pinned by the shared vectors
 * LXMF-rust/tests/display_name_vectors.json, sections `placeholder` and
 * `own_hash_suffix`, which LXMF-rust and Retichat-js run too: the old web
 * node defaults and the old web announce suffix are placeholders, in Kotlin
 * and in the migrations' SQL alike.
 */
class PlaceholderVectorsTest {
    /** The project root (the directory with settings.gradle.kts), whatever the test's working directory. */
    private val projectRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").exists() }
        ?: error("no settings.gradle.kts above ${File("").absolutePath}")

    @Suppress("UNCHECKED_CAST")
    private val vectors: Map<String, Any?> by lazy {
        // Missing vectors fail the test rather than skip it.
        val file = File(projectRoot, "../LXMF-rust/tests/display_name_vectors.json").canonicalFile
        assertTrue("shared vectors not found at $file", file.exists())
        MiniJson.parse(file.readText()) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun section(key: String) = (vectors[key] as List<Map<String, Any?>>).also { assertTrue(key, it.size >= 20) }

    private val db = DriverManager.getConnection("jdbc:sqlite::memory:")

    @After fun close() = db.close()

    private fun sqlBool(expr: String, value: String): Boolean =
        db.prepareStatement("SELECT $expr").use { st ->
            st.setString(1, value)
            st.executeQuery().use { rs -> rs.next(); rs.getInt(1) == 1 }
        }

    @Test
    fun placeholdersAndOldWebNodeDefaultsFollowTheVectors() {
        for (v in section("placeholder")) {
            val input = v["input"] as String
            val what = "${v["name"]}: '$input'"
            assertEquals(what, v["placeholder"], DisplayNames.isPlaceholder(input))
            assertEquals(what, v["web_node_default"], DisplayNames.isWebNodeDefault(input))
            // The migrations' SQL gives the same answers.
            assertEquals("SQL $what", v["placeholder"], sqlBool(NamesMigration.placeholderSql("?1"), input))
            assertEquals("SQL $what", v["web_node_default"], sqlBool(NamesMigration.webNodeDefaultSql("?1"), input))
        }
    }

    @Test
    fun theOldWebAnnounceSuffixFollowsTheVectors() {
        for (v in section("own_hash_suffix")) {
            val input = v["input"] as String
            val hash = v["own_hash"] as String
            val what = "${v["name"]}: '$input'"
            assertEquals(what, v["expected"], DisplayNames.stripOwnHashSuffix(input, hash))
            // The migration's SQL strips exactly where the rule applies, to the same name.
            val sql = "SELECT CASE WHEN ${NamesMigration.ownHashSuffixSql("?1", "?2")} " +
                "THEN ${NamesMigration.withoutOwnHashSuffixSql("?1")} ELSE ?1 END"
            val stripped = db.prepareStatement(sql).use { st ->
                st.setString(1, input); st.setString(2, hash)
                st.executeQuery().use { rs -> rs.next(); rs.getString(1) }
            }
            assertEquals("SQL $what", v["expected"], stripped)
        }
    }

    @Test
    fun anOldWebNameIsNoMessageDisplayName() {
        // §5.4 Settings: Android's "Retichat", the web's "Retichat Web" and an
        // old web node default become empty; white space is §3's.
        assertEquals("", UserPreferences.migratedMessageName("Retichat Web"))
        assertEquals("", UserPreferences.migratedMessageName("Retichat Web (retichat)"))
        assertEquals("", UserPreferences.migratedMessageName("\u0085Retichat\n"))
        assertEquals("Retichat Webber", UserPreferences.migratedMessageName("Retichat Webber"))
        assertEquals("\u001FAlice", UserPreferences.migratedMessageName("\u001FAlice "))
    }
}
