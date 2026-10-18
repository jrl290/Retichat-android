package com.newendian.retichat.ui.conversation

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/**
 * The day markers' zone after a broadcast (L83): a time zone change names the
 * new zone, which the process's default may not be yet when it comes.
 */
class DeviceDayZoneTest {
    private val elsewhere = if (ZoneId.systemDefault().id == "Pacific/Chatham") "Asia/Kathmandu" else "Pacific/Chatham"

    @Test
    fun aTimeZoneChangeTakesTheZoneItNames() {
        assertEquals(ZoneId.of(elsewhere), DeviceDay.zoneAfter(Intent.ACTION_TIMEZONE_CHANGED, elsewhere))
    }

    @Test
    fun otherwiseTheDevicesDefaultZone() {
        assertEquals("a date change names no zone",
            ZoneId.systemDefault(), DeviceDay.zoneAfter(Intent.ACTION_DATE_CHANGED, elsewhere))
        assertEquals(ZoneId.systemDefault(), DeviceDay.zoneAfter(Intent.ACTION_TIMEZONE_CHANGED, null))
        assertEquals("a zone id the clock does not know",
            ZoneId.systemDefault(), DeviceDay.zoneAfter(Intent.ACTION_TIMEZONE_CHANGED, "Not/AZone"))
    }
}
