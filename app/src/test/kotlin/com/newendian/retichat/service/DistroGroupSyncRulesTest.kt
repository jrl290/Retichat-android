package com.newendian.retichat.service

import com.newendian.retichat.bridge.MiniJson
import com.newendian.retichat.data.repository.ChatRepository.DistroGroupOutcome
import com.newendian.retichat.service.DistroGroupSync.Disposition
import com.newendian.retichat.service.DistroGroupSync.Route
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RFed SPEC §17.14 rules 1 and 2 (GROUPS-DISTRO-DESIGN.md §11.1) from real
 * bytes: a group sync G whose source is not D is ignored, and one whose
 * signature does not validate under D's key is dropped, neither recorded as
 * seen. The cases are the JSON the distro unwrap returns for each blob, as the
 * JNI crate's `a_g_whose_source_is_not_d_and_a_forged_g_unwrap_as_the_app_must_drop_them`
 * writes and checks them (rust/retichat-jni/tests/data/distro_group_sync_rules.json):
 * D's own G (the control), the same G from a member M, and the same G claiming
 * D as its source but signed by a forger X. Here they go through what
 * RfedDistroClient.handleBlob and ChatRepository.onDistroGroupSync decide with,
 * and through rule 0's gate (DistroGroupSync.throughRule0) with a record of the
 * seen keys: neither is recorded, so D's own G, which has the same hash, is
 * still applied after them.
 */
class DistroGroupSyncRulesTest {
    @Suppress("UNCHECKED_CAST")
    private val file: Map<String, Any?> by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        MiniJson.parse(File(root, "rust/retichat-jni/tests/data/distro_group_sync_rules.json").readText()) as Map<String, Any?>
    }

    private val d get() = file["distro_hex"] as String
    private val m get() = file["member_hex"] as String
    private val sendingDevice get() = file["device_hex"] as String

    /** This device: not the one that wrote the G, so D's own G is a sibling's. */
    private val thisDevice = "e".repeat(32)

    @Suppress("UNCHECKED_CAST")
    private fun case(name: String): Map<String, Any?> =
        MiniJson.parse((file["cases"] as List<Map<String, Any?>>).single { it["name"] == name }["unwrap"] as String) as Map<String, Any?>

    /**
     * The unwrap's JSON as handleBlob reads it: the group keys (DistroGroupKeys), the route (a
     * group sync by its marker, whatever its source), then the source and the verdict the
     * repository's disposition is decided on.
     */
    private fun decide(unwrap: Map<String, Any?>, distro: String? = d): Disposition {
        val keys = DistroGroupKeys.read { unwrap[it] }
        val route = DistroGroupSync.route(keys.groupSync, keys.hasGroupId, keys.groupId)
        assertTrue("a G is routed as a group sync whatever its source or signature: $route", route is Route.Sync)
        return DistroGroupSync.disposition(
            unwrap["source_hash"] as String, distro, unwrap["signature_validated"] == true, (route as Route.Sync).read, thisDevice,
        )
    }

    @Test
    fun theControlDsOwnGIsAppliedAndTeachesItsSendingDevice() {
        val apply = decide(case("g_from_d")) as Disposition.Apply
        assertEquals("accept", apply.sync.op)
        assertEquals(sendingDevice, apply.sibling)
    }

    @Test
    fun rule1AGWhoseSourceIsNotDIsIgnored() {
        val unwrap = case("g_from_a_member")
        assertEquals(m, unwrap["source_hash"])
        assertEquals(Disposition.NotFromDistro(m), decide(unwrap))
        // Before the device has loaded D, no G is from D.
        assertEquals(Disposition.NotFromDistro(d), decide(case("g_from_d"), distro = null))
    }

    @Test
    fun rule2AGClaimingDButNotSignedUnderDsKeyIsForged() {
        val unwrap = case("g_forged")
        assertEquals(d, unwrap["source_hash"])
        assertEquals(false, unwrap["signature_validated"])
        assertEquals(Disposition.Forged, decide(unwrap))
        // The LXMF hash does not cover the signature: the forgery and D's own G share it, so a
        // forged copy must never record the hash as seen, or D's own would be skipped.
        assertEquals(case("g_from_d")["hash"], unwrap["hash"])
    }

    /** Rule 0's record, as UserPreferences' distro-seen list keeps it. */
    private class Record : DistroGroupSync.SeenRecord {
        val keys = mutableListOf<String>()
        override fun has(key: String): Boolean = key in keys
        override fun mark(key: String) { if (key !in keys) keys += key }
    }

    /**
     * What ChatRepository.onDistroGroupSync makes of each disposition (pinned in its source by
     * [theRepositoryDropsBothWithALineAndRecordsNeitherAsSeen]): rules 1, 2 and 4 drop it; D's own
     * G is kept once applied (the apply table is not under test here) or as an own echo.
     */
    private fun outcomeOf(disposition: Disposition): DistroGroupOutcome = when (disposition) {
        is Disposition.NotFromDistro, Disposition.Forged, is Disposition.BadForm -> DistroGroupOutcome.DROPPED
        Disposition.OwnEcho, is Disposition.Apply -> DistroGroupOutcome.KEPT
    }

    @Test
    fun aForgedGOrOneNotFromDIsNeverRecordedSoDsOwnGIsStillAppliedAfterIt() = runBlocking {
        val own = case("g_from_d")
        val key = DistroGroupSync.seenKey(own["hash"] as String)
        for (name in listOf("g_forged", "g_from_a_member")) {
            val other = case(name)
            val otherKey = DistroGroupSync.seenKey(other["hash"] as String)
            val record = Record()
            var applied = 0
            // It comes first: judged, dropped, not recorded.
            assertEquals(name, DistroGroupOutcome.DROPPED, DistroGroupSync.throughRule0(otherKey, record) { outcomeOf(decide(other)) })
            assertFalse("$name: $otherKey recorded as seen", record.has(otherKey))
            // The forgery has D's own G's hash (the hash does not cover the signature): one key.
            if (name == "g_forged") assertEquals(key, otherKey)
            // D's own G is still judged and applied.
            val kept = DistroGroupSync.throughRule0(key, record) {
                applied++
                outcomeOf(decide(own)).also { assertTrue(decide(own) is Disposition.Apply) }
            }
            assertEquals(name, DistroGroupOutcome.KEPT, kept)
            assertEquals(name, 1, applied)
            assertTrue(record.has(key))
            // A replay of D's own is skipped by rule 0, judged by nothing.
            assertNull(DistroGroupSync.throughRule0(key, record) { applied++; DistroGroupOutcome.KEPT })
            assertEquals(name, 1, applied)
        }
    }

    @Test
    fun onlyAKeptGOrMessageIsRecordedNeverOneHeldOrDropped() = runBlocking {
        for ((outcome, recorded) in listOf(
            DistroGroupOutcome.KEPT to true,
            DistroGroupOutcome.HELD to false,
            DistroGroupOutcome.DROPPED to false,
        )) {
            val record = Record()
            assertEquals(outcome, DistroGroupSync.throughRule0("gsync:x", record) { outcome })
            assertEquals("$outcome", recorded, record.has("gsync:x"))
            assertEquals("$outcome", recorded, DistroGroupSync.recordsSeen(outcome))
        }
    }

    @Test
    fun theRepositoryDropsBothWithALineAndRecordsNeitherAsSeen() {
        val src = File("src/main/kotlin/com/newendian/retichat/data/repository/ChatRepository.kt").readText()
        val sync = src.substringAfter("suspend fun onDistroGroupSync(").substringBefore("fun learnSibling(")
        assertTrue(sync.contains("DistroGroupSync.disposition(srcHex, distro, signatureValidated, read, device)"))
        val notFromDistro = sync.substringAfter("is DistroGroupSync.Disposition.NotFromDistro ->").substringBefore("DistroGroupSync.Disposition.Forged ->")
        assertTrue(notFromDistro.contains("Log.w(TAG,") && notFromDistro.contains("DistroGroupOutcome.DROPPED"))
        val forged = sync.substringAfter("DistroGroupSync.Disposition.Forged ->").substringBefore("DistroGroupSync.Disposition.OwnEcho ->")
        assertTrue(forged.contains("Log.w(TAG,") && forged.contains("DistroGroupOutcome.DROPPED"))
        // The client hands the repository the unwrap's own source and verdict, and records the
        // G's hash through rule 0's gate only (run above), never by hand.
        val client = File("src/main/kotlin/com/newendian/retichat/service/RfedDistroClient.kt").readText()
        assertTrue(client.contains("val srcHex = o.getString(\"source_hash\")"))
        assertTrue(client.contains("handleGroupSync(context, blob, groupKeys, route.read, srcHex, o.optBoolean(\"signature_validated\", false))"))
        val handle = client.substringAfter("private suspend fun handleGroupSync(").substringBefore("private suspend fun handleGroupMessage(")
        assertTrue(handle.contains("DistroGroupSync.throughRule0(key, seenRecord(context)) {\n                app.repository.onDistroGroupSync("))
        assertFalse(handle.contains("markDistroSeen"))
    }
}
