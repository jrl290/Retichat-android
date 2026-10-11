package com.newendian.retichat.bridge

import com.newendian.retichat.data.repository.DeliveryPolicy
import com.newendian.retichat.service.DistroChannelSync
import com.newendian.retichat.service.DistroCodec
import com.newendian.retichat.service.DistroGroupSync
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Kotlin reader of a fields map (LxmfFields), which the direct path and
 * the group branch read group entries with, against LXMF-rust's group sync
 * vectors @16a9902 (rust/retichat-jni/tests/data/distro_group_sync_vectors.json,
 * the copy the JNI crate checks is LXMF-rust's): an ext of an unknown type
 * skipped, a bin GROUP_ID that is no id, a distro marker that makes a message
 * no group message on the distro path and means nothing on the direct path
 * (RFed SPEC §17.14); a map that repeats a key never read from one of its
 * entries (RFed SPEC §17.14 "Repeated keys", James, 2026-10-11: parity with
 * Python LXMF; the native layer refuses such a message whole before the app
 * sees it, pinned by the JNI crate's test); and the invite parser's comma
 * split (`invite_members`). From 5f9edff's copy to 16a9902's the reader kept
 * the first entry of a repeated key and 0xFB from its last; before that the
 * last of every key.
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
    fun theCopyIsTheOneWithTheRefusedAndKeyEqualityVectors() {
        assertEquals(21, list("group_entries").size)
        assertEquals(15, list("variants").size)
        assertEquals(53, list("rejects").size)
        assertEquals(2, list("invite_members").size)
        assertEquals(11, list("refused").size)
        assertEquals(22, list("key_equality").size)
        val names = list("group_entries").map { it["name"] }
        for (name in listOf(
            "group_id_bin_in_both_forms", "group_id_bin_in_0xd1_str_in_0xa0", "ext_beside_group_id",
            "ext_beside_group_id_in_retichat_field", "marker_transfer", "marker_sent", "marker_channel", "marker_group",
        )) assertTrue("group_entries $name", name in names)
        assertFalse("no group_entries vector repeats a key any more", names.any { it.toString().contains("twice") })
        assertEquals(
            listOf(
                "group_id_twice", "group_id_twice_one_malformed", "retichat_field_group_id_twice", "retichat_field_twice",
                "group_action_and_name_twice", "custom_type_twice_marker_first", "custom_type_twice_marker_last", "g_leave_0xfb_twice",
                "retichat_field_nested_map_repeats_a_key", "non_group_field_twice", "non_group_field_nested_repeat",
            ),
            list("refused").map { it["name"] },
        )
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
    fun eachGroupEntriesVectorReadsOnTheDirectPathAsOnTheDistrosButForTheMarker() {
        // RFed SPEC §17.14, "On the direct path too": a distro marker means nothing there. Every
        // marker_* vector carries 0xA0, 0xA3 and 0xA2 and key 1 of 0xD1 beside its marker.
        var markers = 0
        for (vector in list("group_entries")) {
            val name = vector["name"] as String
            @Suppress("UNCHECKED_CAST")
            val expected = vector["expected"] as Map<String, Any?>
            val got = fields(vector).directGroupEntries()
            if (name.startsWith("marker_")) {
                markers++
                assertEquals(name, GroupEntryReading(true, "9f8e7d6c5b4a39281706f5e4d3c2b1a0", "invite", "Hike — Sunday", false), got)
            } else {
                assertEquals("$name: has_group_id", expected["has_group_id"], got.hasGroupId)
                assertEquals("$name: group_id", expected["group_id"], got.groupId)
                assertEquals("$name: group_action", expected["group_action"], got.action)
                assertEquals("$name: group_name", expected["group_name"], got.name)
                assertFalse("$name: group_sync", got.groupSync)
            }
        }
        assertEquals(4, markers)
    }

    @Test
    fun noRefusedVectorIsReadHereFromOneOfItsEntries() {
        // The native layer refuses each of these whole (the JNI crate's
        // the_unwrap_and_the_receipt_refuse_a_message_that_repeats_a_key), so the app is never given
        // their fields. Each repeats an integer key, which this reader compares: given one anyway,
        // it reads no fields at all rather than one entry of the key.
        for (vector in list("refused")) {
            assertSame(vector["name"].toString(), LxmfFields.EMPTY, fields(vector))
        }
        val jni = File("../rust/retichat-jni/src/group_status.rs").readText()
        val test = jni.substringAfter("fn the_unwrap_and_the_receipt_refuse_a_message_that_repeats_a_key()")
        assertTrue(test.contains("assert_eq!(refused.len(), 11);"))
        assertTrue(test.contains("assert_eq!(pairs.len(), 22);"))
    }

    @Test
    fun aMapThatRepeatsAKeyAtAnyDepthIsNeverReadFromOneOfItsEntries() {
        val a = "a".repeat(32)
        val b = "b".repeat(32)
        fun str(s: String) = byteArrayOf(0xD9.toByte(), s.length.toByte()) + s.toByteArray()
        // [0xA0: "a"*32, 0xA0: "b"*32]: the fields map repeats 0xA0.
        val top = byteArrayOf(0x82.toByte()) +
            byteArrayOf(0xCC.toByte(), 0xA0.toByte()) + str(a) + byteArrayOf(0xCC.toByte(), 0xA0.toByte()) + str(b)
        assertSame(LxmfFields.EMPTY, LxmfFields.decode(top))
        // [0xFB: "x", 0xFB: "y"]: 0xFB too (until 2026-10-11 read from its last).
        val custom = byteArrayOf(0x82.toByte()) +
            byteArrayOf(0xCC.toByte(), 0xFB.toByte(), 0xA1.toByte(), 'x'.code.toByte()) +
            byteArrayOf(0xCC.toByte(), 0xFB.toByte(), 0xA1.toByte(), 'y'.code.toByte())
        assertSame(LxmfFields.EMPTY, LxmfFields.decode(custom))
        // [0xD1: {1: "a"*32, 1: "b"*32}]: the Retichat field's map repeats its key 1.
        val nested = byteArrayOf(0x81.toByte(), 0xCC.toByte(), 0xD1.toByte(), 0x82.toByte()) + byteArrayOf(0x01) + str(a) + byteArrayOf(0x01) + str(b)
        assertSame(LxmfFields.EMPTY, LxmfFields.decode(nested))
        // [0x09: [{1: 7, 1: 8}]]: a map in an array.
        val inArray = byteArrayOf(0x81.toByte(), 0x09, 0x91.toByte(), 0x82.toByte(), 0x01, 0x07, 0x01, 0x08)
        assertSame(LxmfFields.EMPTY, LxmfFields.decode(inArray))
        // The same entries without the repeat are read.
        val once = byteArrayOf(0x82.toByte()) +
            byteArrayOf(0xCC.toByte(), 0xA0.toByte()) + str(a) + byteArrayOf(0xCC.toByte(), 0xFB.toByte(), 0xA1.toByte(), 'x'.code.toByte())
        val f = LxmfFields.decode(once)
        assertEquals(a, f.groupEntries().groupId)
        assertEquals("x", f.getString(LxmfFields.FIELD_CUSTOM_TYPE))
    }

    @Test
    fun theDirectPathAndTheGroupBranchReadTheGroupIdOnceEachPathItsOwnWay() {
        val repo = File("src/main/kotlin/com/newendian/retichat/data/repository/ChatRepository.kt").readText()
        val statuses = File("src/main/kotlin/com/newendian/retichat/data/repository/GroupStatuses.kt").readText()
        assertFalse(repo.contains("fields.group(GroupEntry.ID)"))
        assertFalse(statuses.contains("fields.group(GroupEntry.ID)"))
        // The direct path classifies through DeliveryPolicy.directRoute; the group handler takes the
        // id its path read and never reads it again (the distro reading would drop a marker's).
        assertTrue(repo.contains("val route = DeliveryPolicy.directRoute(fields)"))
        assertFalse(repo.contains("fields.groupEntries()"))
        assertTrue(repo.contains("handleGroupMessage(msgId, groupId, srcHex,"))
        assertTrue(repo.contains("handleGroupMessage(hash, groupId, srcHex,"))
        // A status element is checked as if it had arrived directly.
        assertTrue(statuses.contains("val about = fields.directGroupEntries().groupId ?: return \"no group id\""))
    }
}
