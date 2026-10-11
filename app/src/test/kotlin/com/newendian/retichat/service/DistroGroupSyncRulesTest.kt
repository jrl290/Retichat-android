package com.newendian.retichat.service

import com.newendian.retichat.bridge.MiniJson
import com.newendian.retichat.service.DistroGroupSync.Disposition
import com.newendian.retichat.service.DistroGroupSync.Route
import org.junit.Assert.assertEquals
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
 * RfedDistroClient.handleBlob and ChatRepository.onDistroGroupSync decide with.
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
        // G's hash only when the repository kept it.
        val client = File("src/main/kotlin/com/newendian/retichat/service/RfedDistroClient.kt").readText()
        assertTrue(client.contains("val srcHex = o.getString(\"source_hash\")"))
        assertTrue(client.contains("handleGroupSync(context, blob, groupKeys, route.read, srcHex, o.optBoolean(\"signature_validated\", false))"))
        val handle = client.substringAfter("private suspend fun handleGroupSync(").substringBefore("private suspend fun handleGroupMessage(")
        val mark = handle.indexOf("UserPreferences.markDistroSeen(context, key)")
        assertTrue(mark > handle.indexOf("if (outcome == com.newendian.retichat.data.repository.ChatRepository.DistroGroupOutcome.KEPT) {"))
    }
}
