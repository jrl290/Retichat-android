package com.newendian.retichat.data.repository

import com.newendian.retichat.bridge.LxmfFields
import com.newendian.retichat.bridge.MiniJson
import com.newendian.retichat.data.repository.DeliveryPolicy.DirectRoute
import com.newendian.retichat.service.DistroCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What a message that arrived directly, on the device's own address, is
 * (DeliveryPolicy.directRoute; RFed SPEC §17.14, "On the direct path too"): a
 * distro sent copy is dropped and an identity transfer offered first (iOS and
 * the web refuse a malformed GROUP_ID before either; that order is open for
 * James); then, by its group id whatever its 0xFB, a group message, or a
 * GROUP_ID that is not 32 hex refused with a log line, never a DM or a group;
 * then a DM. The `group_entries` vectors (LXMF-rust @16a9902) give the readings,
 * their marker_* cases a GROUP_ID beside each of the four distro markers.
 * Until 2026-10-11 a `rfed.distro.channel` or `rfed.distro.group` marker
 * beside a GROUP_ID made the message a DM on this path. A message whose
 * fields repeat a key is refused whole before any of this (RFed SPEC §17.14
 * "Repeated keys"), and nesting in a field nothing reads never empties the map.
 */
class DirectRouteTest {
    @Suppress("UNCHECKED_CAST")
    private val v: Map<String, Any?> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        MiniJson.parse(File(root, "rust/retichat-jni/tests/data/distro_group_sync_vectors.json").readText()) as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun list(key: String) = v[key] as List<Map<String, Any?>>

    private fun fields(vector: Map<String, Any?>) = LxmfFields.decode(DistroCodec.hexToBytes(vector["fields_hex"] as String)!!)

    private fun route(name: String) = DeliveryPolicy.directRoute(fields(list("group_entries").single { it["name"] == name }))

    private val id = "9f8e7d6c5b4a39281706f5e4d3c2b1a0"

    @Test
    fun aChannelOrGroupMarkerBesideAGroupIdIsAGroupMessageOnTheDirectPath() {
        assertEquals(DirectRoute.Group(id), route("marker_channel"))
        assertEquals(DirectRoute.Group(id), route("marker_group"))
    }

    @Test
    fun aSentCopyIsDroppedAndATransferOfferedWhateverGroupIdTheyCarry() {
        assertEquals(DirectRoute.SentCopy, route("marker_sent"))
        assertEquals(DirectRoute.Transfer("ab".repeat(16)), route("marker_transfer"))
        // A transfer whose GROUP_ID is malformed is offered all the same here. iOS (cd2282f) and
        // the web (3a0efa6) refuse it: they look at the GROUP_ID first. Which order is right is
        // open for James (RFed SPEC §17.14, 2026-10-11); this pins Android's until it is ruled.
        val transfer = map(
            key(0xFB) to str("rfed.distro.transfer"), key(0xFC) to str("ab".repeat(16)), key(0xA0) to byteArrayOf(0x05),
        )
        assertEquals(DirectRoute.Transfer("ab".repeat(16)), DeliveryPolicy.directRoute(LxmfFields.decode(transfer)))
    }

    @Test
    fun everyOtherGroupEntriesVectorIsAGroupARefusalOrADmByItsGroupId() {
        for (vector in list("group_entries")) {
            val name = vector["name"] as String
            if (name.startsWith("marker_")) continue
            @Suppress("UNCHECKED_CAST")
            val expected = vector["expected"] as Map<String, Any?>
            val want = when {
                expected["has_group_id"] != true -> DirectRoute.Direct
                expected["group_id"] == null -> DirectRoute.BadGroupId
                else -> DirectRoute.Group(expected["group_id"] as String)
            }
            assertEquals(name, want, DeliveryPolicy.directRoute(fields(vector)))
        }
        // Read lowercased; a bin, an int, nil or a short id is refused.
        assertEquals(DirectRoute.Group(id), route("group_id_uppercase"))
        for (name in listOf("group_id_int", "group_id_nil", "group_id_short", "group_id_bin_in_both_forms", "retichat_field_int_id_only")) {
            assertEquals(name, DirectRoute.BadGroupId, route(name))
        }
        assertEquals(DirectRoute.Direct, route("no_group_entries"))
    }

    @Test
    fun aGroupSyncOrAMembershipMessageThatReachesTheDeviceDirectlyIsNoGroupMessage() {
        // A G carries no GROUP_ID: on the direct path its marker means nothing, so it is a DM,
        // under the privacy filter, as on iOS and the web.
        for (set in listOf("ops", "variants")) {
            for (vector in list(set)) assertEquals("$set ${vector["name"]}", DirectRoute.Direct, DeliveryPolicy.directRoute(fields(vector)))
        }
    }

    @Test
    fun theDirectPathRefusesABadGroupIdWithALineBeforeAnythingIsStored() {
        val repo = File("src/main/kotlin/com/newendian/retichat/data/repository/ChatRepository.kt").readText()
        val received = repo.substringAfter("fun onMessageReceived(").substringBefore("\n    /**")
        val route = received.indexOf("val route = DeliveryPolicy.directRoute(received)")
        val refused = received.indexOf("DeliveryPolicy.DirectRoute.BadGroupId -> {")
        val launch = received.indexOf("scope.launch(Dispatchers.IO) {")
        assertTrue(route in 0 until refused)
        assertTrue(refused < launch)
        val branch = received.substring(refused, received.indexOf("else -> Unit", refused))
        assertTrue(branch.contains("Log.i(TAG, \"onMessageReceived: REFUSED group message from"))
        assertTrue(branch.contains("return"))
        // The group branch takes the id the route read; nothing reads 0xFB's distro reading here.
        assertTrue(received.contains("val groupId = (route as? DeliveryPolicy.DirectRoute.Group)?.groupId"))
        assertTrue(received.contains("val transferKey = (route as? DeliveryPolicy.DirectRoute.Transfer)?.key"))
        assertTrue(received.contains("handleGroupMessage(msgId, groupId, srcHex,"))
    }

    @Test
    fun aMessageWhoseFieldsRepeatAKeyIsRefusedWholeNeverADm() {
        // The router refuses each of these before the app sees it (LXMF-rust 16a9902). A native
        // library built before that delivers them; the app then refuses them itself, whatever
        // else they carry, and never files one as a DM (as 4d337d1 did, silently).
        val refused = list("refused")
        assertEquals(11, refused.size)
        for (vector in refused) {
            val raw = DistroCodec.hexToBytes(vector["fields_hex"] as String)!!
            var reported: Throwable? = null
            val route = DeliveryPolicy.directRoute(LxmfFields.receive(raw, onFailure = { reported = it }))
            assertTrue("${vector["name"]}: $route", route is DirectRoute.Refused)
            assertTrue("${vector["name"]}: ${(route as DirectRoute.Refused).why}", route.why.contains("repeats the key"))
            // A refusal, not an unreadable map: nothing is stored, so there is no "storing it without them" line.
            assertEquals(vector["name"].toString(), null, reported)
        }
        // A map without a repeat is read.
        assertEquals(DirectRoute.Group(id), DeliveryPolicy.directRoute(LxmfFields.receive(map(key(0xA0) to str(id)))))
    }

    @Test
    fun theDirectPathRefusesARepeatedKeyWithOneLineBeforeAnythingElse() {
        val repo = File("src/main/kotlin/com/newendian/retichat/data/repository/ChatRepository.kt").readText()
        val received = repo.substringAfter("fun onMessageReceived(").substringBefore("\n    /**")
        val read = received.indexOf("val received = LxmfFields.receive(fieldsRaw, onFailure = {")
        val refused = received.indexOf("if (route is DeliveryPolicy.DirectRoute.Refused) {")
        assertTrue(read in 0 until refused)
        val branch = received.substring(refused, received.indexOf("val fields = (received as? LxmfFields.Received.Read)?.fields ?: return", refused))
        assertTrue(branch.contains("Log.w(TAG, \"onMessageReceived: REFUSED the message from"))
        assertTrue(branch.contains("return"))
        // Before the name is decoded, the other routes are acted on, and anything is written.
        for (later in listOf("RetichatBridge.displayNameDecode(fieldsRaw)", "DeliveryPolicy.DirectRoute.SentCopy -> {", "scope.launch(Dispatchers.IO) {")) {
            assertTrue(later, refused < received.indexOf(later))
        }
        // The distro paths refuse it too, never filing it as a DM or a group post.
        for ((decl, line) in listOf(
            "fun onDistroMessageReceived(" to "onDistroMessageReceived: REFUSED the message from",
            "suspend fun onDistroGroupMessage(" to "REFUSED whole: \${received.why}",
        )) {
            val body = repo.substringAfter(decl).substringBefore("\n    /**")
            val at = body.indexOf("is LxmfFields.Received.Refused -> {")
            assertTrue(decl, at >= 0 && body.indexOf(line, at) > at)
        }
        assertTrue(!repo.contains("decodeOrEmpty"))
    }

    @Test
    fun nestingInAFieldNothingReadsNeverEmptiesTheMap() {
        // {0xA0: id, 0x09: 70 nested one-element arrays}: LXMF-rust and Python accept it, and iOS and
        // the web read its GROUP_ID. Until 2026-10-11 this reader stopped at 64 containers, read the
        // map as no fields, and filed it as a DM, silently.
        assertEquals("control, 60 deep", DirectRoute.Group(id), deepRoute(id, 60))
        assertEquals(DirectRoute.Group(id), deepRoute(id, 70))
        assertEquals(DirectRoute.Group(id), deepRoute(id.uppercase(), 70))
        assertEquals(DirectRoute.BadGroupId, deepRoute("xyz", 70))
        // As deep as the bytes allow, without recursion: no StackOverflowError, the id still read.
        assertEquals(DirectRoute.Group(id), deepRoute(id, 200_000))
        assertEquals(DirectRoute.BadGroupId, deepRoute("xyz", 200_000))
    }

    @Test
    fun fieldsThatCannotBeReadAreReportedNeverSwallowed() {
        // A map of one entry whose str is cut short: read as no fields, and reported, so the
        // repository's "unreadable" line is written. Until 2026-10-11 the decoder swallowed it.
        var reported: Throwable? = null
        val got = LxmfFields.receive(byteArrayOf(0x81.toByte(), 0x01, 0xA5.toByte(), 0x61), onFailure = { reported = it })
        assertEquals(LxmfFields.Received.Read(LxmfFields.EMPTY), got)
        assertTrue("reported: $reported", reported is IllegalArgumentException)
        // Cut short inside a value stepped over: the same.
        reported = null
        LxmfFields.receive(deep(id, 70).copyOf(deep(id, 70).size - 1), onFailure = { reported = it })
        assertTrue("reported: $reported", reported != null)
    }

    /** {0xA0: [groupId], 0x09: [depth] nested one-element arrays around an empty one}. */
    private fun deep(groupId: String, depth: Int): ByteArray =
        byteArrayOf(0x82.toByte()) + key(0xA0) + str(groupId) + key(0x09) +
            ByteArray(depth - 1) { 0x91.toByte() } + byteArrayOf(0x90.toByte())

    private fun deepRoute(groupId: String, depth: Int): DirectRoute = DeliveryPolicy.directRoute(LxmfFields.receive(deep(groupId, depth)))

    // ---- msgpack bytes ----

    private fun str(s: String): ByteArray =
        (if (s.length < 32) byteArrayOf((0xA0 or s.length).toByte()) else byteArrayOf(0xD9.toByte(), s.length.toByte())) + s.toByteArray()
    private fun key(k: Int): ByteArray = if (k < 0x80) byteArrayOf(k.toByte()) else byteArrayOf(0xCC.toByte(), k.toByte())
    private fun map(vararg entries: Pair<ByteArray, ByteArray>): ByteArray =
        entries.fold(byteArrayOf((0x80 or entries.size).toByte())) { acc, (k, value) -> acc + k + value }
}
