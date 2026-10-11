package com.newendian.retichat.service

import com.newendian.retichat.bridge.MiniJson
import com.newendian.retichat.service.DistroGroupSync.Disposition
import com.newendian.retichat.service.DistroGroupSync.Effect
import com.newendian.retichat.service.DistroGroupSync.LeftEffect
import com.newendian.retichat.service.DistroGroupSync.Local
import com.newendian.retichat.service.DistroGroupSync.Route
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The client's part of the group sync (RFed SPEC §17.14; GROUPS-DISTRO-DESIGN.md
 * §7, §10, §11): the JSON the bridge gives and takes, read against the shared
 * vectors (rust/retichat-jni/tests/data/distro_group_sync_vectors.json, the copy
 * the JNI crate's Rust tests check against LXMF-rust), the receive rules 1-4,
 * the apply table of §11.2, `left`, and the distro path's routing (§6.1).
 */
class DistroGroupSyncTest {
    @Suppress("UNCHECKED_CAST")
    private val v: Map<String, Any?> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val file = File(root, "rust/retichat-jni/tests/data/distro_group_sync_vectors.json")
        check(file.exists()) { "shared vectors not found at $file" }
        MiniJson.parse(file.readText()) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun list(key: String) = v[key] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun hex(key: String) = (v[key] as Map<String, Any?>)["lxmf_delivery_hash_hex"] as String

    private val d get() = hex("distro")
    private val m get() = hex("member")
    private val device get() = v["device_hex"] as String
    private val sibling = "5".repeat(32)

    private fun read(obj: Map<String, Any?>) = DistroGroupSync.read { obj[it] }

    @Suppress("UNCHECKED_CAST")
    private fun parse(json: String) = MiniJson.parse(json) as Map<String, Any?>

    // ---- reading the unwrap's group_sync, writing the bridge's input ----

    @Test
    fun everyOpsExpectedReadingIsReadWholeAndWrittenBackAsTheBridgeTakesIt() {
        // The vectors' `expected` is the §7 group_sync object a receiver reports; `sync` is the writer's input.
        @Suppress("UNCHECKED_CAST")
        for (op in list("ops") + list("variants")) {
            val expected = op["expected"] as Map<String, Any?>
            val read = read(expected)
            assertNull(op["name"].toString(), read.problem)
            val sync = read.sync!!
            assertEquals(expected["by"], read.by)
            val written = parse(sync.toJson())
            // Every key the writer takes, in its value, null where the op does not carry it (GroupSync::from_json).
            assertEquals(op["name"].toString(), expected - "by" - "problem", written)
        }
        assertEquals(8, list("ops").size)
    }

    @Test
    fun theWriterInputOfEachOpIsTheVectorsSyncKeyForKey() {
        @Suppress("UNCHECKED_CAST")
        for (op in list("ops")) {
            val sync = op["sync"] as Map<String, Any?>
            val ours = read(sync + ("by" to "") + ("problem" to null)).sync!!
            assertEquals(op["name"].toString(), sync, parse(ours.toJson()))
        }
    }

    @Test
    fun aProblemOrAShapeTheBridgeNeverWritesIsAProblemNeverASync() {
        val leave = mapOf("by" to device.uppercase(), "problem" to null, "op" to "leave", "group_id" to "9f8e7d6c5b4a39281706f5e4d3c2b1a0", "at_ms" to 5L, "left" to emptyList<String>())
        assertNotNull(read(leave).sync)
        assertEquals("by is read lowercased", device, read(leave).by)
        val problem = read(leave + ("problem" to "left holds 33 ids, more than 32"))
        assertEquals("left holds 33 ids, more than 32", problem.problem)
        assertNull(problem.sync)
        for (broken in listOf(
            leave - "op",
            leave + ("op" to "join"),
            leave + ("group_id" to "9f8e"),
            leave - "at_ms",
            leave + ("at_ms" to 9_007_199_254_740_992L),
            leave + ("at_ms" to -1L),
            leave + ("at_ms" to 1.5),
            leave + ("left" to List(33) { "a".repeat(32) }),
            leave + ("left" to listOf("a".repeat(31))),
            leave + ("left" to "aa"),
            leave + ("op" to "accept"),
            leave + ("op" to "post"),
        )) {
            val r = read(broken)
            assertNull(broken.toString(), r.sync)
            assertNotNull(broken.toString(), r.problem)
        }
        // org.json may give an integral at_ms as a Double: it is read by value.
        assertEquals(1_790_000_100_456L, read(leave + ("at_ms" to 1_790_000_100_456.0)).sync!!.atMs)
    }

    @Test
    fun theJsonWrittenEscapesWhatAJsonStringMust() {
        val post = GroupSync(DistroGroupSync.POST, "a".repeat(32), 7, text = "say \"hi\"\\\n\u0001 — ok")
        assertEquals("say \"hi\"\\\n\u0001 — ok", parse(post.toJson())["text"])
        val accept = GroupSync(DistroGroupSync.ACCEPT, "a".repeat(32), 7, name = "a\"b", members = "x", keys = listOf("k\"1"), creator = "c".repeat(32))
        val back = parse(accept.toJson())
        assertEquals("a\"b", back["name"])
        assertEquals(listOf("k\"1"), back["keys"])
        assertEquals("c".repeat(32), back["creator"])
        assertNull(back["text"])
    }

    // ---- §11.1 rules 1-4 ----

    private fun goodRead(by: String = sibling) = GroupSyncRead(by, null, GroupSync(DistroGroupSync.LEAVE, "a".repeat(32), 1))

    @Test
    fun theSourceMustBeDTheSignatureDsAndTheOwnEchoIsRecognisedBeforeTheForm() {
        // Rule 1: anyone can encrypt to D, and the source is plaintext inside it.
        assertEquals(Disposition.NotFromDistro(m), DistroGroupSync.disposition(m, d, true, goodRead(), device))
        assertEquals(Disposition.NotFromDistro(d), DistroGroupSync.disposition(d, null, true, goodRead(), device))
        // Rule 2: the source is D but the signature is not D's (a forged create would make every sibling the creator).
        assertEquals(Disposition.Forged, DistroGroupSync.disposition(d, d, false, goodRead(), device))
        // Rule 3: this device's own G, even with a form it would drop.
        assertEquals(Disposition.OwnEcho, DistroGroupSync.disposition(d, d, true, goodRead(by = device), device))
        assertEquals(Disposition.OwnEcho, DistroGroupSync.disposition(d, d, true, GroupSyncRead(device, "bad", null), device))
        // Rule 4: the form; the sibling was learned at rule 3 all the same.
        assertEquals(Disposition.BadForm("bad", sibling), DistroGroupSync.disposition(d, d, true, GroupSyncRead(sibling, "bad", null), device))
        val apply = DistroGroupSync.disposition(d, d, true, goodRead(), device) as Disposition.Apply
        assertEquals(sibling, apply.sibling)
        // A missing or malformed 0xFD teaches nothing.
        assertNull((DistroGroupSync.disposition(d, d, true, goodRead(by = ""), device) as Disposition.Apply).sibling)
        assertNull(DistroGroupSync.siblingOf("xyz", device))
        assertNull(DistroGroupSync.siblingOf(device, device))
    }

    // ---- §11.2 the apply table ----

    private val creator = "c".repeat(32)
    private fun sync(op: String, members: String = "$d,$m,$creator", creatorOf: String? = creator) = when (op) {
        DistroGroupSync.CREATE -> GroupSync(op, "a".repeat(32), 1, name = "G", members = members, keys = emptyList())
        DistroGroupSync.ACCEPT -> GroupSync(op, "a".repeat(32), 1, name = "G", members = members, keys = emptyList(), creator = creatorOf)
        DistroGroupSync.POST -> GroupSync(op, "a".repeat(32), 1, text = "")
        else -> GroupSync(op, "a".repeat(32), 1)
    }

    private val pendingAsD = Local.Held(ownIsD = true, joined = false, creator = creator, members = setOf(d, m, creator))
    private val joinedAsD = pendingAsD.copy(joined = true)
    private val heldAsDevice = pendingAsD.copy(ownIsD = false, joined = true)

    @Test
    fun createHoldsTheGroupDiscardsAnyPendingInviteAndChangesNothingJoinedOrClosed() {
        val create = sync(DistroGroupSync.CREATE)
        assertEquals(Effect.HoldFromG(d, discarded = false), DistroGroupSync.effect(create, Local.NotHeld, d))
        assertEquals(Effect.HoldFromG(d, discarded = true), DistroGroupSync.effect(create, pendingAsD, d))
        assertEquals(Effect.Nothing, DistroGroupSync.effect(create, joinedAsD, d))
        assertEquals(Effect.Nothing, DistroGroupSync.effect(create, Local.Closed, d))
    }

    @Test
    fun anAcceptJoinsOnlyThePendingInviteItDescribesAndNeverReopensAClosedGroup() {
        val accept = sync(DistroGroupSync.ACCEPT)
        assertEquals(Effect.HoldFromG(creator, discarded = false), DistroGroupSync.effect(accept, Local.NotHeld, d))
        assertEquals(Effect.JoinPending, DistroGroupSync.effect(accept, pendingAsD, d))
        // The same list in another order is the same list.
        assertEquals(Effect.JoinPending, DistroGroupSync.effect(sync(DistroGroupSync.ACCEPT, members = "$creator, $m,$d"), pendingAsD, d))
        // Another inviter's version for the same id (M invited D to X): discarded, held from G with G's creator.
        val fromM = pendingAsD.copy(creator = m)
        assertEquals(Effect.HoldFromG(creator, discarded = true), DistroGroupSync.effect(accept, fromM, d))
        assertEquals(Effect.HoldFromG(creator, discarded = true), DistroGroupSync.effect(sync(DistroGroupSync.ACCEPT, members = "$d,$creator"), pendingAsD, d))
        assertEquals(Effect.Nothing, DistroGroupSync.effect(accept, joinedAsD, d))
        // A later-stamped accept after a leave does not reopen it.
        assertEquals(Effect.Nothing, DistroGroupSync.effect(accept.copy(atMs = Long.MAX_VALUE / 4), Local.Closed, d))
    }

    @Test
    fun aLeaveClosesWhatIsHeldAndTombstonesWhatIsNot() {
        val leave = sync(DistroGroupSync.LEAVE)
        assertEquals(Effect.RecordClosed, DistroGroupSync.effect(leave, Local.NotHeld, d))
        assertEquals(Effect.Close, DistroGroupSync.effect(leave, pendingAsD, d))
        assertEquals(Effect.Close, DistroGroupSync.effect(leave, joinedAsD, d))
        assertEquals(Effect.Nothing, DistroGroupSync.effect(leave, Local.Closed, d))
    }

    @Test
    fun aPostIsStoredInAJoinedGroupHeldForAPendingOrUnheldOneAndDroppedForAClosedOne() {
        val post = sync(DistroGroupSync.POST)
        assertEquals(Effect.HoldPost, DistroGroupSync.effect(post, Local.NotHeld, d))
        assertEquals(Effect.HoldPost, DistroGroupSync.effect(post, pendingAsD, d))
        assertEquals(Effect.StorePost, DistroGroupSync.effect(post, joinedAsD, d))
        assertEquals(Effect.DropPost, DistroGroupSync.effect(post, Local.Closed, d))
    }

    @Test
    fun aGroupHeldAsTheDeviceIgnoresEveryOpRule5() {
        for (op in DistroGroupSync.OPS) {
            assertEquals(op, Effect.IgnoreOwnAddress, DistroGroupSync.effect(sync(op), heldAsDevice, d))
            assertEquals(op, Effect.IgnoreOwnAddress, DistroGroupSync.effect(sync(op), heldAsDevice.copy(joined = false), d))
        }
    }

    @Test
    fun leftClosesADGroupTombstonesAnUnheldOneAndLeavesADeviceGroupAlone() {
        assertEquals(LeftEffect.CLOSE, DistroGroupSync.leftEffect(joinedAsD))
        assertEquals(LeftEffect.CLOSE, DistroGroupSync.leftEffect(pendingAsD))
        assertEquals(LeftEffect.RECORD_CLOSED, DistroGroupSync.leftEffect(Local.NotHeld))
        assertEquals(LeftEffect.ALREADY_CLOSED, DistroGroupSync.leftEffect(Local.Closed))
        assertEquals(LeftEffect.IGNORE, DistroGroupSync.leftEffect(heldAsDevice))
    }

    @Test
    fun theLeftListIsMostRecentFirstEachOnceAtMost32() {
        val a = "a".repeat(32); val b = "b".repeat(32); val c = "c".repeat(32)
        assertEquals(listOf(c, a, b), DistroGroupSync.recordLeft(listOf(a, b), listOf(c)))
        assertEquals(listOf(b, a), DistroGroupSync.recordLeft(listOf(a, b), listOf(b.uppercase())))
        val full = List(32) { it.toString(16).padStart(32, '0') }
        val next = DistroGroupSync.recordLeft(full, listOf(a))
        assertEquals(32, next.size)
        assertEquals(a, next.first())
        assertFalse("the oldest is forgotten first", full.last() in next)
    }

    @Test
    fun aReceivedLeaveRecordsItsOwnIdThenItsLeftInGOrderInOneStepAsEveryClientMust() {
        // The rule the review of 8d330c4 asks all three clients to share, so that a full list
        // forgets the same ids everywhere: [op id] + the G's `left` in its order, in one call,
        // moved to the front, each once, cut to 32. A leave G for 9 with left [9, a, b, c] on [0].
        val (g9, a, b, c, g0) = listOf("9", "a", "b", "c", "0").map { it.repeat(32) }
        assertEquals(listOf(g9, a, b, c, g0), DistroGroupSync.recordLeft(listOf(g0), listOf(g9) + listOf(g9, a, b, c)))
        // An id already on the list moves to the front.
        assertEquals(listOf(g9, a, g0, b), DistroGroupSync.recordLeft(listOf(g0, a, b), listOf(g9, a)))
        // applyGroupSync builds that list: the op's id first, then `left` in G order, one call.
        val repo = File("src/main/kotlin/com/newendian/retichat/data/repository/ChatRepository.kt").readText()
        val apply = repo.substring(repo.indexOf("private suspend fun applyGroupSync("), repo.indexOf("private suspend fun holdGroupFromSync("))
        assertTrue(apply.indexOf("leftNow += sync.groupId") in 0 until apply.indexOf("for (id in sync.left) {"))
        assertEquals(1, Regex("UserPreferences\\.recordGroupsLeft\\(").findAll(apply).count())
        assertTrue(apply.indexOf("for (id in sync.left) {") < apply.indexOf("UserPreferences.recordGroupsLeft(appContext, distro, leftNow)"))
    }

    // ---- §6.1 routing and §6.2 keys ----

    @Test
    fun theGroupSyncRoutesFirstThenAGroupMessageByHasGroupIdNeverByTheIdThenTheRest() {
        val read = goodRead()
        assertEquals(Route.Sync(read), DistroGroupSync.route(read, hasGroupId = true, groupId = "a".repeat(32)))
        assertEquals(Route.Message("a".repeat(32)), DistroGroupSync.route(null, true, "A".repeat(32)))
        // A GROUP_ID that is an int (the unwrap: has_group_id true, group_id null) is dropped in the group branch, never a DM.
        assertEquals(Route.BadGroupId, DistroGroupSync.route(null, true, null))
        assertEquals(Route.BadGroupId, DistroGroupSync.route(null, true, "abc"))
        assertEquals(Route.Other, DistroGroupSync.route(null, false, null))
        val h = "e".repeat(64)
        assertEquals("gsync:$h", DistroGroupSync.seenKey(h))
        assertEquals("grp:$h", DistroGroupSync.messageSeenKey(h))
        assertEquals("group:$h", DistroGroupSync.entryId(h))
    }

    @Test
    fun theUnwrapsNewKeysAreReadAndAnythingElseReadsAsAbsent() {
        val key = "ab".repeat(64)
        val packed = byteArrayOf(1, 2, 3, 4)
        val json = """{"hash":"${"E".repeat(64)}","has_group_id":true,"group_id":"${"9F".repeat(16)}","group_action":"accept",""" +
            """"group_name":"G","group_sync":null,"sender_key":"$key","packed":"${java.util.Base64.getEncoder().encodeToString(packed)}"}"""
        val o = parse(json)
        val k = DistroGroupKeys.read { o[it] }
        assertEquals("e".repeat(64), k.hash)
        assertTrue(k.hasGroupId)
        assertEquals("9f".repeat(16), k.groupId)
        assertEquals("accept", k.groupAction)
        assertEquals("G", k.groupName)
        assertNull(k.groupSync)
        assertEquals(64, k.senderKey!!.size)
        assertArrayEquals(packed, k.packed)
        val none = DistroGroupKeys.read { null }
        assertNull(none.hash); assertFalse(none.hasGroupId); assertNull(none.senderKey); assertNull(none.packed); assertNull(none.groupSync)
        val odd = parse("""{"hash":"xyz","has_group_id":"yes","sender_key":"ab","packed":"%%%"}""")
        val k2 = DistroGroupKeys.read { odd[it] }
        assertNull(k2.hash); assertFalse(k2.hasGroupId); assertNull(k2.senderKey); assertNull(k2.packed)
        @Suppress("UNCHECKED_CAST")
        val expected = list("ops").first()["expected"] as Map<String, Any?>
        val withSync = DistroGroupKeys.read { if (it == "group_sync") expected else null }
        assertEquals(DistroGroupSync.CREATE, withSync.groupSync!!.sync!!.op)
    }
}
