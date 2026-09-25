package com.newendian.retichat.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeliveryTargetsTest {
    @Test
    fun aDeliveryReportFindsItsRowOnce() {
        val targets = DeliveryTargets()
        targets.remember("aa", DeliveryTargets.Target.Row("out_1"))
        assertEquals(DeliveryTargets.Target.Row("out_1"), targets.take("aa"))
        assertNull("a second report upgrades nothing", targets.take("aa"))
    }

    @Test
    fun aGroupMemberCopyKeepsItsMember() {
        val targets = DeliveryTargets()
        val member = DeliveryTargets.Target.GroupMember("grp_1", "chat", "bb")
        targets.remember("cc", member)
        assertEquals(member, targets.take("cc"))
    }

    @Test
    fun theOldestSendsAreForgottenFirst() {
        val targets = DeliveryTargets(capacity = 2)
        targets.remember("h1", DeliveryTargets.Target.Row("m1"))
        targets.remember("h2", DeliveryTargets.Target.Row("m2"))
        targets.remember("h3", DeliveryTargets.Target.Row("m3"))
        assertNull(targets.take("h1"))
        assertEquals(DeliveryTargets.Target.Row("m2"), targets.take("h2"))
        assertEquals(DeliveryTargets.Target.Row("m3"), targets.take("h3"))
    }
}
