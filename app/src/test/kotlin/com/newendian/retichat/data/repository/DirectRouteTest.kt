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
 * (DeliveryPolicy.directRoute; RFed SPEC §17.14, "On the direct path too", the
 * order decided 2026-10-11 for James): a GROUP_ID that is not 32 hex refused
 * with a log line first, whatever else the message carries; then a distro sent
 * copy dropped, an identity transfer offered, a group message by its group id
 * whatever its 0xFB, and a DM. iOS and the web classify so; until 2026-10-11
 * Android looked at the sent-copy and transfer markers before the GROUP_ID. The `group_entries` vectors (LXMF-rust @16a9902) give the readings,
 * their marker_* cases a GROUP_ID beside each of the four distro markers.
 * Until 2026-10-11 a `rfed.distro.channel` or `rfed.distro.group` marker
 * beside a GROUP_ID made the message a DM on this path. A message whose
 * fields Python LXMF refuses whole is refused before any of this (RFed SPEC
 * §17.14 "Repeated keys", "Unhashable keys", "Nesting"), and nesting in a
 * field nothing reads never empties the map: it is read to depth 493.
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
    fun aMalformedGroupIdIsRefusedFirstThenASentCopyIsDroppedAndATransferOffered() {
        // Beside a well-formed GROUP_ID, the sent-copy and transfer markers still decide (steps 2 and 3).
        assertEquals(DirectRoute.SentCopy, route("marker_sent"))
        assertEquals(DirectRoute.Transfer("ab".repeat(16)), route("marker_transfer"))
        // Beside a malformed one, the message is refused (step 1), as iOS (cd2282f) and the web (3a0efa6) do.
        // Until 2026-10-11 Android offered such a transfer and dropped such a sent copy.
        for ((type, what) in listOf("rfed.distro.transfer" to "a transfer", "rfed.distro.sent" to "a sent copy")) {
            for (malformed in listOf(byteArrayOf(0x05), str("xyz"), byteArrayOf(0xC0.toByte()), str("ab".repeat(15)))) {
                val fields = map(key(0xFB) to str(type), key(0xFC) to str("ab".repeat(16)), key(0xA0) to malformed)
                assertEquals(what, DirectRoute.BadGroupId, DeliveryPolicy.directRoute(LxmfFields.decode(fields)))
            }
            // Control: with no GROUP_ID at all, the marker decides.
            val bare = map(key(0xFB) to str(type), key(0xFC) to str("ab".repeat(16)))
            val want = if (type == "rfed.distro.sent") DirectRoute.SentCopy else DirectRoute.Transfer("ab".repeat(16))
            assertEquals("$what without a GROUP_ID", want, DeliveryPolicy.directRoute(LxmfFields.decode(bare)))
        }
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
    fun aMessageTheReferenceRefusesIsRefusedWholeNeverADm() {
        // The router refuses each of these before the app sees it (LXMF-rust 16a9902 for a repeated
        // key, b873c00 for a key Python cannot hash and nesting past depth 493). A native library
        // built before that delivers them; the app then refuses them itself, whatever else they
        // carry, for the reference's reason, and never files one as a DM (as 4d337d1 did, silently).
        // Until 2026-10-11 (this change) it refused only a repeated integer key.
        val refused = list("refused")
        assertEquals(22, refused.size)
        val vectors = refused + list("key_equality").filter { it["refused"] == true } + list("nesting").filter { it["refused"] == true }
        assertEquals(22 + 15 + 6, vectors.size)
        for (vector in vectors) {
            val raw = DistroCodec.hexToBytes(vector["fields_hex"] as String)!!
            var reported: Throwable? = null
            val route = DeliveryPolicy.directRoute(LxmfFields.receive(raw, onFailure = { reported = it }))
            assertTrue("${vector["name"]}: $route", route is DirectRoute.Refused)
            val words = when (vector["reason"] ?: "repeated_key") {
                "repeated_key" -> "repeats a key"
                "unhashable_key" -> "has a key Python cannot hash"
                else -> "is nested past depth 493"
            }
            assertTrue("${vector["name"]}: ${(route as DirectRoute.Refused).why}", route.why.contains(words))
            // A refusal, not an unreadable map: nothing is stored, so there is no "storing it without them" line.
            assertEquals(vector["name"].toString(), null, reported)
        }
        // A map without a repeat is read, and so is every key_equality pair the reference reads.
        assertEquals(DirectRoute.Group(id), DeliveryPolicy.directRoute(LxmfFields.receive(map(key(0xA0) to str(id)))))
        for (vector in list("key_equality").filter { it["refused"] == false }) {
            val raw = DistroCodec.hexToBytes(vector["fields_hex"] as String)!!
            assertEquals(vector["name"].toString(), DirectRoute.Direct, DeliveryPolicy.directRoute(LxmfFields.receive(raw)))
        }
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
    fun nestingInAFieldNothingReadsIsReadToTheLimitAndRefusedPastIt() {
        // {0xA0: id, 0x09: 70 nested one-element arrays}: LXMF-rust and Python accept it, and iOS and
        // the web read its GROUP_ID. Until 2026-10-11 this reader stopped at 64 containers, read the
        // map as no fields, and filed it as a DM, silently.
        assertEquals("control, 60 deep", DirectRoute.Group(id), deepRoute(id, 60))
        assertEquals(DirectRoute.Group(id), deepRoute(id, 70))
        assertEquals(DirectRoute.Group(id), deepRoute(id.uppercase(), 70))
        assertEquals(DirectRoute.BadGroupId, deepRoute("xyz", 70))
        // RFed SPEC §17.14 "Nesting": 492 arrays in a field, the innermost empty at depth 493, are
        // read; one more is refused whole, whatever the GROUP_ID, as Python LXMF refuses it.
        assertEquals(DirectRoute.Group(id), deepRoute(id, 492))
        for (groupId in listOf(id, "xyz")) {
            val past = deepRoute(groupId, 493)
            assertTrue("$past", past is DirectRoute.Refused && past.why.contains("a value at depth 494 is nested past depth 493"))
        }
        // Far past it: refused at depth 494, its later bytes never walked, and no StackOverflowError.
        // Until 2026-10-11 (this change) the id was read from 200 000 nested arrays.
        for (groupId in listOf(id, "xyz")) {
            val far = deepRoute(groupId, 200_000)
            assertTrue("$far", far is DirectRoute.Refused && far.why.contains("a value at depth 494 is nested past depth 493"))
        }
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
