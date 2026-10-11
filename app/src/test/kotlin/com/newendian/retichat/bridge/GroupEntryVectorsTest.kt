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
 * vectors @b873c00 (rust/retichat-jni/tests/data/distro_group_sync_vectors.json,
 * the copy the JNI crate checks is LXMF-rust's): an ext of an unknown type
 * skipped, a bin GROUP_ID that is no id, a distro marker that makes a message
 * no group message on the distro path and means nothing on the direct path
 * (RFed SPEC §17.14); bytes Python LXMF refuses whole (the `refused`,
 * `key_equality` and `nesting` sets: a repeated key by Python's equality, a
 * key Python cannot hash, nesting past depth 493) never read from one of their
 * entries, and a receive path's read of them a refusal for the reference's
 * reason (RFed SPEC §17.14 "Repeated keys", James, 2026-10-11: parity with
 * Python LXMF; "Unhashable keys", "Nesting", decided 2026-10-11; the native
 * layer refuses such a message whole before the app sees it, pinned by the
 * JNI crate's test); and the invite parser's comma split (`invite_members`).
 * From 5f9edff's copy to 16a9902's the reader kept the first entry of a
 * repeated key and 0xFB from its last; before that the last of every key.
 * Until the b873c00 round it refused only a repeated integer key.
 */
class GroupEntryVectorsTest {
    @Suppress("UNCHECKED_CAST")
    private val v: Map<String, Any?> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        MiniJson.parse(File(root, "rust/retichat-jni/tests/data/distro_group_sync_vectors.json").readText()) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun list(key: String) = v[key] as List<Map<String, Any?>>

    private fun fields(vector: Map<String, Any?>) = LxmfFields.decode(raw(vector))

    private fun raw(vector: Map<String, Any?>) = DistroCodec.hexToBytes(vector["fields_hex"] as String)!!

    /**
     * The words each of the reference's refusals is given in (LXMF-rust
     * msgpack_keys.rs `Refusal`), by a vector's `reason`; a refused
     * key_equality pair has none and repeats a key.
     */
    private fun words(vector: Map<String, Any?>) = when (vector["reason"] ?: "repeated_key") {
        "repeated_key" -> "repeats a key"
        "unhashable_key" -> "has a key Python cannot hash"
        "too_deep" -> "is nested past depth 493"
        else -> throw AssertionError("${vector["name"]}: reason ${vector["reason"]}")
    }

    /** A receive path's read of [vector]'s fields, which must never be unreadable. */
    private fun received(vector: Map<String, Any?>) =
        LxmfFields.receive(raw(vector), onFailure = { throw AssertionError("${vector["name"]}: unreadable", it) })

    @Test
    fun theCopyIsTheOneWithTheRefusedAndKeyEqualityVectors() {
        assertEquals(21, list("group_entries").size)
        assertEquals(15, list("variants").size)
        assertEquals(53, list("rejects").size)
        assertEquals(2, list("invite_members").size)
        assertEquals(22, list("refused").size)
        assertEquals(24, list("key_equality").size)
        assertEquals(11, list("nesting").size)
        assertEquals(493L, v["nesting_max_depth"])
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
                "map_key_beside_group_id", "map_key_not_empty", "array_key_holding_a_map", "array_key_holding_a_map_deeper",
                "retichat_field_map_key", "non_group_field_nested_map_key", "g_leave_map_key", "map_key_that_repeats_a_key",
                "map_key_whose_value_repeats_a_key", "array_key_holding_a_map_whose_value_repeats_a_key",
                "array_key_holding_a_map_then_a_repeat",
            ),
            list("refused").map { it["name"] },
        )
        assertEquals(mapOf("repeated_key" to 13, "unhashable_key" to 9), list("refused").groupingBy { it["reason"] }.eachCount())
        assertEquals(15, list("key_equality").count { it["refused"] == true })
        assertEquals(6, list("nesting").count { it["refused"] == true })
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
        // their fields. Given one anyway (a native library built before LXMF-rust b873c00), this
        // reader refuses it as the reference does, for the reference's first reason: a repeated key,
        // a key Python cannot hash (RFed SPEC §17.14 "Unhashable keys"). It reads no fields at all,
        // never one entry of a key. Until 2026-10-11 (this change) it refused only a repeated
        // integer key, in the containers it reads.
        for (vector in list("refused")) {
            val name = vector["name"].toString()
            assertSame(name, LxmfFields.EMPTY, fields(vector))
            // A receive path's read is a refusal: the message is dropped, never a DM (DirectRouteTest).
            val received = received(vector)
            assertTrue("$name: $received", received is LxmfFields.Received.Refused)
            assertTrue("$name: $received", (received as LxmfFields.Received.Refused).why.contains(words(vector)))
        }
        val jni = File("../rust/retichat-jni/src/group_status.rs").readText()
        val test = jni.substringAfter("fn the_unwrap_and_the_receipt_refuse_a_message_that_repeats_a_key()")
        assertTrue(test.contains("assert_eq!(refused.len(), 22);"))
        assertTrue(test.contains("assert_eq!(pairs.len(), 24);"))
        assertTrue(test.contains("assert_eq!(nesting.len(), 11);"))
    }

    @Test
    fun eachKeyEqualityPairIsRefusedExactlyWhenTheReferenceRefusesIt() {
        // Two keys of one map are one key when Python says so (RFed SPEC §17.14 "Repeated keys"):
        // 1, 0xcc 0x01, 1.0 and true; 0 and -0.0; nil and nil; two equal str, bin or ext; a
        // timestamp by its instant. NaN, a str and a bin, two array keys: never one key.
        for (vector in list("key_equality")) {
            val name = vector["name"].toString()
            val received = received(vector)
            if (vector["refused"] == true) {
                assertTrue("$name: $received", received is LxmfFields.Received.Refused)
                assertTrue("$name: $received", (received as LxmfFields.Received.Refused).why.contains(words(vector)))
            } else {
                assertTrue("$name: $received", received is LxmfFields.Received.Read)
            }
        }
    }

    @Test
    fun eachNestingVectorIsReadToDepth493AndRefusedPastIt() {
        // RFed SPEC §17.14 "Nesting" (decided 2026-10-11: one limit): the fields map at depth 1, a
        // field's value at 2, and no value deeper than 493, a key's included.
        for (vector in list("nesting")) {
            val name = vector["name"].toString()
            val received = received(vector)
            if (vector["refused"] == true) {
                assertTrue("$name: $received", received is LxmfFields.Received.Refused)
                assertTrue("$name: $received", (received as LxmfFields.Received.Refused).why.contains(words(vector)))
            } else {
                assertTrue("$name: $received", received is LxmfFields.Received.Read)
                assertEquals(name, 1, (received as LxmfFields.Received.Read).fields.size)
            }
        }
    }

    @Test
    fun aMapTheReaderReadsThatRepeatsAKeyIsNeverReadFromOneOfItsEntries() {
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
        // [0x09: [[{1: 7, 1: 8}]]]: a map below the deepest container an accessor reads. Its value
        // is never read, but Python refuses the message for it all the same, so it is refused here
        // too. Until 2026-10-11 (this change) it was stepped over and the map read.
        val below = byteArrayOf(0x81.toByte(), 0x09, 0x91.toByte(), 0x91.toByte(), 0x82.toByte(), 0x01, 0x07, 0x01, 0x08)
        assertSame(LxmfFields.EMPTY, LxmfFields.decode(below))
        // [0x09: [[{"a": 7, "a": 8}]]]: a str key repeated, which no accessor reads either.
        val strKeys = byteArrayOf(0x81.toByte(), 0x09, 0x91.toByte(), 0x91.toByte(), 0x82.toByte(), 0xA1.toByte(), 0x61, 0x07, 0xA1.toByte(), 0x61, 0x08)
        assertSame(LxmfFields.EMPTY, LxmfFields.decode(strKeys))
        for (bytes in listOf(top, custom, nested, inArray, below, strKeys)) {
            val received = LxmfFields.receive(bytes)
            assertTrue("$received", received is LxmfFields.Received.Refused && received.why.contains("repeats a key"))
        }
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
        assertTrue(repo.contains("val route = DeliveryPolicy.directRoute(received)"))
        assertFalse(repo.contains("fields.groupEntries()"))
        assertTrue(repo.contains("handleGroupMessage(msgId, groupId, srcHex,"))
        assertTrue(repo.contains("handleGroupMessage(hash, groupId, srcHex,"))
        // A status element is checked as if it had arrived directly.
        assertTrue(statuses.contains("val about = fields.directGroupEntries().groupId ?: return \"no group id\""))
    }
}
