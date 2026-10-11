package com.newendian.retichat.bridge

import com.newendian.retichat.data.repository.DeliveryPolicy
import com.newendian.retichat.service.DistroChannelSync
import com.newendian.retichat.service.DistroCodec
import com.newendian.retichat.service.DistroGroupSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Kotlin reader of a fields map (LxmfFields), which the direct path and
 * the group branch read group entries with, against LXMF-rust's group sync
 * vectors @5f9edff (rust/retichat-jni/tests/data/distro_group_sync_vectors.json,
 * the copy the JNI crate checks is LXMF-rust's): RFed SPEC §17.14 "Repeated
 * keys" (a group entry from the first entry with its key, 0xFB from the last),
 * an ext of an unknown type skipped, a bin GROUP_ID that is no id, a distro
 * marker that makes a message no group message; and the invite parser's
 * comma split (`invite_members`). Until 2026-10-11 the reader kept the last of
 * a repeated key everywhere, as a dictionary does, so a message whose GROUP_ID
 * came twice was read for the second group here and for the first by
 * LXMF-rust, iOS and the web.
 */
class GroupEntryVectorsTest {
    @Suppress("UNCHECKED_CAST")
    private val v: Map<String, Any?> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        MiniJson.parse(File(root, "rust/retichat-jni/tests/data/distro_group_sync_vectors.json").readText()) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun list(key: String) = v[key] as List<Map<String, Any?>>

    private fun fields(vector: Map<String, Any?>) = LxmfFields.decode(DistroCodec.hexToBytes(vector["fields_hex"] as String)!!)

    @Test
    fun theCopyIsTheOneWithTheRepeatedKeyAndCommaVectors() {
        assertEquals(28, list("group_entries").size)
        assertEquals(14, list("variants").size)
        assertEquals(53, list("rejects").size)
        assertEquals(2, list("invite_members").size)
        val names = list("group_entries").map { it["name"] }
        for (name in listOf(
            "group_id_twice_the_first_read", "group_id_twice_the_first_malformed", "retichat_field_group_id_twice",
            "retichat_field_twice", "group_action_and_name_twice", "group_id_bin_in_both_forms", "group_id_bin_in_0xd1_str_in_0xa0",
            "ext_beside_group_id", "ext_beside_group_id_in_retichat_field", "custom_type_twice_the_last_not_a_marker",
            "custom_type_twice_the_last_a_marker",
        )) assertTrue("group_entries $name", name in names)
    }

    @Test
    fun eachGroupEntriesVectorReadsAsLxmfRustReadsIt() {
        for (vector in list("group_entries")) {
            val name = vector["name"]
            @Suppress("UNCHECKED_CAST")
            val expected = vector["expected"] as Map<String, Any?>
            val got = fields(vector).groupEntries()
            assertEquals("$name: has_group_id", expected["has_group_id"], got.hasGroupId)
            assertEquals("$name: group_id", expected["group_id"], got.groupId)
            assertEquals("$name: group_action", expected["group_action"], got.action)
            assertEquals("$name: group_name", expected["group_name"], got.name)
            assertEquals("$name: group_sync", expected["group_sync"], got.groupSync)
        }
    }

    @Test
    fun everyGroupSyncVectorIsMarkedAndNoGroupMessage() {
        // A G's group entries are never read (§17.14 step 2): every op, variant and reject is a G by its last 0xFB.
        for (set in listOf("ops", "variants", "rejects")) {
            for (vector in list(set)) {
                val got = fields(vector).groupEntries()
                assertTrue("$set ${vector["name"]}", got.groupSync)
                assertFalse("$set ${vector["name"]}", got.hasGroupId)
                assertNull(got.groupId)
            }
        }
        // The markers are the four the spec names, the ones the clients write.
        assertEquals(setOf(LxmfFields.DISTRO_TRANSFER_TYPE, LxmfFields.DISTRO_SENT_TYPE, DistroChannelSync.TYPE, DistroGroupSync.TYPE), LxmfFields.DISTRO_MARKERS)
        assertEquals(DistroGroupSync.TYPE, LxmfFields.DISTRO_GROUP_TYPE)
    }

    @Test
    fun theInviteParserSplitsOnTheCodePointComma() {
        for (vector in list("invite_members")) {
            assertEquals(vector["name"].toString(), vector["members"], DeliveryPolicy.members(vector["csv"] as String))
        }
    }

    @Test
    fun aRejectAsTheBridgeReportsItIsAProblemNeverASync() {
        // The JNI crate's Rust test runs each reject through distro_unwrap_json; what reaches Kotlin is its `by` and a problem.
        for (vector in list("rejects")) {
            val read = DistroGroupSync.read { key -> mapOf("by" to vector["by"], "problem" to vector["why"])[key] }
            assertNull(vector["name"].toString(), read.sync)
            assertNotNull(read.problem)
            assertEquals(vector["by"], read.by)
        }
    }

    @Test
    fun aRepeatedKeyIsReadFromTheFirstEntryAndTheCustomFieldsFromTheLast() {
        // [0xA0: "a"*32, 0xA0: "b"*32, 0xFB: "x", 0xFB: "y", 0xFC: 1, 0xFC: 2, 0x02: 7, 0x02: 8]
        val a = "a".repeat(32)
        val b = "b".repeat(32)
        fun str(s: String) = byteArrayOf(0xD9.toByte(), s.length.toByte()) + s.toByteArray()
        val bytes = byteArrayOf(0x88.toByte()) +
            byteArrayOf(0xCC.toByte(), 0xA0.toByte()) + str(a) + byteArrayOf(0xCC.toByte(), 0xA0.toByte()) + str(b) +
            byteArrayOf(0xCC.toByte(), 0xFB.toByte(), 0xA1.toByte(), 'x'.code.toByte()) + byteArrayOf(0xCC.toByte(), 0xFB.toByte(), 0xA1.toByte(), 'y'.code.toByte()) +
            byteArrayOf(0xCC.toByte(), 0xFC.toByte(), 0x01) + byteArrayOf(0xCC.toByte(), 0xFC.toByte(), 0x02) +
            byteArrayOf(0x02, 0x07) + byteArrayOf(0x02, 0x08)
        val f = LxmfFields.decode(bytes)
        assertEquals(a, f.group(GroupEntry.ID))
        assertEquals(a, f.groupEntries().groupId)
        // LXMF's custom fields 0xFB-0xFD from the last (LXMF-rust custom_type / custom_field), any other key from the first.
        assertEquals("y", f.getString(LxmfFields.FIELD_CUSTOM_TYPE))
        assertEquals(2L, f.getInt(LxmfFields.FIELD_CUSTOM_DATA))
        assertEquals(7L, f.getInt(LxmfFields.FIELD_TELEMETRY))
    }

    @Test
    fun theDirectPathAndTheGroupBranchReadTheGroupIdThroughTheOneReader() {
        val repo = File("src/main/kotlin/com/newendian/retichat/data/repository/ChatRepository.kt").readText()
        val statuses = File("src/main/kotlin/com/newendian/retichat/data/repository/GroupStatuses.kt").readText()
        assertFalse(repo.contains("fields.group(GroupEntry.ID)"))
        assertFalse(statuses.contains("fields.group(GroupEntry.ID)"))
        assertTrue(repo.contains("val entries = fields.groupEntries()"))
        // §17.14 step 2 on the direct path too: a GROUP_ID that is not 32 hex is dropped, never shown as a DM.
        val drop = repo.indexOf("if (entries.hasGroupId && groupId == null) {")
        assertTrue(drop >= 0)
        assertTrue(drop < repo.indexOf("DeliveryPolicy.firstRules(signature, srcHex,"))
        assertTrue(repo.contains("val groupId = fields.groupEntries().groupId ?: run {"))
        assertTrue(statuses.contains("val about = fields.groupEntries().groupId ?: return \"no group id\""))
    }
}
