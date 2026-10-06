package com.newendian.retichat.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * After the propagation stream is (re)opened, the device pulls what rfed
 * deferred while its previous link was dead, as the web does after its
 * binding (staging, 2026-10-06: a sibling's channel leave, pushed in the
 * seconds before the re-open, never arrived). Checked in the source, as the
 * other contract tests here are.
 */
class PropagationStreamPullTest {
    private val source = File("src/main/kotlin/com/newendian/retichat/service/PropagationStream.kt").readText()

    @Test
    fun anAcceptedOpenIsFollowedByOnePull() {
        val accepted = source.indexOf("if (openAccepted(response)) {")
        val rejected = source.indexOf("} else {", accepted)
        assertTrue("sendOpen's accepted branch not found", accepted >= 0 && rejected > accepted)
        val branch = source.substring(accepted, rejected)
        assertTrue("an accepted open must pull the deferred queue", branch.contains("RfedDistroClient.pull(app)"))
    }
}
