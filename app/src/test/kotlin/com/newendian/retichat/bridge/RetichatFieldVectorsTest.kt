package com.newendian.retichat.bridge

import com.newendian.retichat.service.GroupFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * LXMF-rust/DISPLAY_NAMES.md §2.1 and §10: the Retichat field 0xD1, run
 * against the shared vectors (LXMF-rust/tests/retichat_field_vectors.json,
 * the same file LXMF-rust, Retichat-ios and Retichat-js run). The name
 * (key 0) is decoded by Rust (nativeDisplayNameDecode) and checked there;
 * here every case's nine group entries go through the Kotlin decoder, and
 * both send forms through [GroupFields].
 */
class RetichatFieldVectorsTest {
    /** The project root (the directory with settings.gradle.kts), whatever the test's working directory. */
    private val projectRoot: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").exists() }
        ?: error("no settings.gradle.kts above ${File("").absolutePath}")

    @Suppress("UNCHECKED_CAST")
    private val vectors: Map<String, Any?> by lazy {
        // Retichat-android sits beside LXMF-rust in the workspace. Missing
        // vectors fail the test rather than skip it: a skipped contract
        // check reads as a pass.
        val file = File(projectRoot, "../LXMF-rust/tests/retichat_field_vectors.json").canonicalFile
        assertTrue("shared vectors not found at $file", file.exists())
        MiniJson.parse(file.readText()) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun list(key: String) = vectors[key] as List<Map<String, Any?>>

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** The vectors' JSON name for [entry]: group_id ... group_member_keys. */
    private fun jsonName(entry: GroupEntry) = "group_" + entry.name.lowercase()

    @Test
    fun theKeysMatchTheSharedTable() {
        val keys = list("keys")
        assertEquals(10, keys.size)
        val name = keys.single { it["key"] == 0L }
        assertEquals("name", name["type"])
        assertEquals("RF_DISPLAY_NAME", name["constant"])
        assertNull(name["legacy_field"])
        assertEquals(0, LxmfFields.RF_DISPLAY_NAME)
        assertEquals(0xD1, LxmfFields.FIELD_RETICHAT)
        assertEquals(9, GroupEntry.entries.size)
        for (entry in GroupEntry.entries) {
            val k = keys.single { it["name"] == jsonName(entry) }
            assertEquals(entry.name, k["key"], entry.key.toLong())
            assertEquals(entry.name, k["legacy_field"], entry.legacyField.toLong())
            assertEquals(entry.name, k["type"], if (entry.type == GroupEntry.Type.BOOL) "bool" else "str")
            // The RF_ constant of that name holds the same key.
            val constant = LxmfFields::class.java.getDeclaredField(k["constant"] as String)
            assertEquals(entry.name, entry.key, constant.getInt(null))
        }
    }

    @Test
    fun everyDecodeVectorReadsItsGroupEntries() {
        val cases = list("decode")
        assertEquals(33, cases.size)
        for (case in cases) {
            val fields = LxmfFields.decode(hex(case["fields_msgpack_hex"] as String))
            @Suppress("UNCHECKED_CAST")
            val expected = case["group"] as Map<String, Any?>
            assertEquals(case["name"] as String, 9, expected.size)
            for (entry in GroupEntry.entries) {
                val got: Any? = if (entry.type == GroupEntry.Type.BOOL) fields.groupBool(entry) else fields.group(entry)
                assertEquals("${case["name"]}: ${jsonName(entry)}", expected[jsonName(entry)], got)
            }
        }
    }

    @Test
    fun aWideKeyDoesNotWrapOntoAGroupField() {
        // Top-level key 0x1_0000_00A0 as uint64: not 0xA0. Before the key
        // range check, toInt() cut it to 0xA0 and it read as the group id.
        val fields = LxmfFields.decode(hex("81cf00000001000000a0a3616263"))
        assertNull(fields.group(GroupEntry.ID))
        // Inside 0xD1 too: key 0x1_0000_0001 is not key 1.
        val inMap = LxmfFields.decode(hex("81ccd181cf0000000100000001a3616263"))
        assertNull(inMap.group(GroupEntry.ID))
        // And a negative key is never a field.
        assertNull(LxmfFields.decode(hex("81ff81cca0a3616263")).group(GroupEntry.ID))
    }

    @Test
    fun theReadersRefuseTheOtherType() {
        val fields = LxmfFields.decode(hex("80"))
        assertThrows { fields.group(GroupEntry.RELAY_DONE) }
        assertThrows { fields.groupBool(GroupEntry.ID) }
    }

    // ── Sending (§10) ─────────────────────────────────────────────────

    /** Records the native calls a [GroupFields.Sink] would make. */
    private class Recorder : GroupFields.Sink {
        val calls = mutableListOf<String>()
        override fun addFieldString(key: Int, value: String) = calls.add("field $key str $value")
        override fun addFieldBool(key: Int, value: Boolean) = calls.add("field $key bool $value")
        override fun setRetichatString(key: Int, value: String) = calls.add("retichat $key str $value")
        override fun setRetichatBool(key: Int, value: Boolean) = calls.add("retichat $key bool $value")
    }

    private fun send(entries: List<Map<String, Any?>>, inRetichatField: Boolean): List<String> {
        val sink = Recorder()
        for (e in entries) {
            val entry = GroupEntry.entries.single { it.key.toLong() == e["key"] }
            when (val v = e["value"]) {
                is String -> assertTrue(GroupFields.set(sink, entry, v, inRetichatField))
                is Boolean -> assertTrue(GroupFields.set(sink, entry, v, inRetichatField))
                else -> error("value $v")
            }
        }
        return sink.calls
    }

    /**
     * Both send forms, for every encode vector. The bytes each form produces
     * (legacy_hex / retichat_hex: set order in the old fields, ascending keys
     * in the map, merged beside the router's name) are the native setters'
     * and pinned by LXMF-rust against the same vectors; here, that the app
     * makes exactly one call per entry, in order, with the vectors' field
     * number or key and type.
     */
    @Test
    fun bothSendFormsForEveryEncodeVector() {
        val keys = list("keys")
        val cases = list("encode")
        assertEquals(5, cases.size)
        for (case in cases) {
            @Suppress("UNCHECKED_CAST")
            val entries = case["entries"] as List<Map<String, Any?>>
            val type = { e: Map<String, Any?> -> if (e["value"] is Boolean) "bool" else "str" }
            val legacy = entries.map { e ->
                val field = keys.single { it["key"] == e["key"] }["legacy_field"] as Long
                "field $field ${type(e)} ${e["value"]}"
            }
            val retichat = entries.map { e -> "retichat ${e["key"]} ${type(e)} ${e["value"]}" }
            assertEquals(case["name"] as String, legacy, send(entries, inRetichatField = false))
            assertEquals(case["name"] as String, retichat, send(entries, inRetichatField = true))
            // The default is the constant: the old form until the switch.
            assertEquals(case["name"] as String, legacy, run {
                val sink = Recorder()
                for (e in entries) {
                    val entry = GroupEntry.entries.single { it.key.toLong() == e["key"] }
                    when (val v = e["value"]) {
                        is String -> GroupFields.set(sink, entry, v)
                        is Boolean -> GroupFields.set(sink, entry, v)
                    }
                }
                sink.calls
            })
        }
    }

    @Test
    fun groupEntriesStayInTheOldFieldsUntilTheSwitch() {
        // §10: false until around 2026-10-26, when every client sets it true.
        assertFalse(LxmfFields.GROUP_ENTRIES_IN_RETICHAT_FIELD)
    }

    @Test
    fun theWritersRefuseTheOtherType() {
        assertThrows { GroupFields.set(Recorder(), GroupEntry.RELAY_DONE, "true", false) }
        assertThrows { GroupFields.set(Recorder(), GroupEntry.ID, true, true) }
    }

    private fun assertThrows(block: () -> Unit) {
        val threw = try { block(); false } catch (_: IllegalArgumentException) { true }
        assertTrue("expected IllegalArgumentException", threw)
    }
}
