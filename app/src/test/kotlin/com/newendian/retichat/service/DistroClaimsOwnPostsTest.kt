package com.newendian.retichat.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager

/**
 * A channel post from the distro that arrived before this device held it was
 * stored as another poster's and stayed so (James, 2026-10-20: "iOS doesn't
 * recognize that a Channel message sent from Distro on one device belongs to
 * the same Distro on another device"). Once the device holds the distro,
 * those posts are the user's own: outgoing, SENT.
 */
class DistroClaimsOwnPostsTest {

    private val dao = File("src/main/kotlin/com/newendian/retichat/data/db/dao/ChannelDao.kt").readText()
    private val client = File("src/main/kotlin/com/newendian/retichat/service/RfedChannelClient.kt").readText()

    /** ChannelDao.claimOwnPosts's SQL, run on the table's columns. */
    @Test
    fun theDistrosPostsBecomeOwnAndNothingElseChanges() {
        val sql = Regex("@Query\\(\"([^\"]+)\"\\)\\s*suspend fun claimOwnPosts\\(sourceHashHex: String\\): Int")
            .find(dao)?.groupValues?.get(1)
        assertTrue("claimOwnPosts found", sql != null)
        val distro = "69dec3e9835305c5a711af19ab7a52e8"
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().execute(
                "CREATE TABLE channel_messages (id TEXT PRIMARY KEY, channelId TEXT, sourceHashHex TEXT, " +
                    "isOutbound INTEGER, sendState INTEGER)"
            )
            db.createStatement().execute(
                "INSERT INTO channel_messages VALUES " +
                    // From the distro, stored as another poster's (one in capitals).
                    "('a', 'c', '$distro', 0, 1), ('b', 'c', '${distro.uppercase()}', 0, 1), " +
                    // Another poster's; this device's own, failed; another channel's distro post.
                    "('c', 'c', '799791b4000000000000000000000000', 0, 1), ('d', 'c', '$distro', 1, 2), " +
                    "('e', 'x', '$distro', 0, 1)"
            )
            val update = db.prepareStatement(sql!!.replace(":sourceHashHex", "?"))
            update.setString(1, distro)
            assertEquals(3, update.executeUpdate())
            val rows = db.createStatement().executeQuery("SELECT id, isOutbound, sendState FROM channel_messages ORDER BY id")
            val got = buildList { while (rows.next()) add("${rows.getString(1)}:${rows.getInt(2)}:${rows.getInt(3)}") }
            assertEquals(listOf("a:1:1", "b:1:1", "c:0:1", "d:1:2", "e:1:1"), got)
        }
    }

    /** At start-up and at every later adopt; nothing when no distro is held. */
    @Test
    fun theClaimFollowsTheDistroTheDeviceHolds() {
        val claim = client.substringAfter("private val distroClaim: Job = scope.launch {").substringBefore("\n    }\n")
        assertTrue(claim.contains("DistroManager.state.map { it?.deliveryHashHex }.distinctUntilChanged().collect { hex ->"))
        assertTrue(claim.contains("if (hex == null) return@collect"))
        assertTrue(claim.contains("channelDao.claimOwnPosts(hex)"))
    }
}
