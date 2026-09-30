package com.newendian.retichat.ui.conversation

import com.newendian.retichat.ui.conversation.DayMarker.Kind.OTHER_YEAR
import com.newendian.retichat.ui.conversation.DayMarker.Kind.THIS_YEAR
import com.newendian.retichat.ui.conversation.DayMarker.Kind.TODAY
import com.newendian.retichat.ui.conversation.DayMarker.Kind.YESTERDAY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.util.Locale

/**
 * The date-marker rules (DayMarkers): a marker above a message whose calendar
 * day, in the viewer's zone, differs from the message shown directly above
 * it, and above the first message of the loaded list; worded Today,
 * Yesterday, a date, or a date with its year.
 */
class DayMarkersTest {
    private val london = ZoneId.of("Europe/London")
    private val newYork = ZoneId.of("America/New_York")
    private val losAngeles = ZoneId.of("America/Los_Angeles")
    private val tokyo = ZoneId.of("Asia/Tokyo")
    private val utc = ZoneId.of("UTC")

    /** Rules as of local [now] in [zone]. */
    private fun markersAt(zone: ZoneId, now: LocalDateTime, sameYear: ((LocalDate, LocalDate) -> Boolean)? = null): DayMarkers {
        val clock = Clock.fixed(now.atZone(zone).toInstant(), zone)
        return if (sameYear == null) DayMarkers(clock) else DayMarkers(clock, sameYear)
    }

    /** The instant of local time [local] in [zone], in milliseconds. */
    private fun ms(zone: ZoneId, local: String): Long =
        LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    private fun marker(date: String, kind: DayMarker.Kind) = DayMarker(LocalDate.parse(date), kind)

    // ---- Same day, first item ----

    @Test
    fun sameDay_onlyTheFirstMessageHasAMarker() {
        val m = markersAt(london, LocalDateTime.parse("2026-09-30T15:00"))
        val list = listOf(ms(london, "2026-09-30T09:00"), ms(london, "2026-09-30T09:01"), ms(london, "2026-09-30T14:59"))
        assertEquals(listOf(marker("2026-09-30", TODAY), null, null), m.forList(list))
    }

    @Test
    fun firstItem_alwaysHasAMarker() {
        val m = markersAt(london, LocalDateTime.parse("2026-09-30T15:00"))
        assertEquals(listOf(marker("2026-09-30", TODAY)), m.forList(listOf(ms(london, "2026-09-30T14:00"))))
        assertEquals(marker("2026-09-29", YESTERDAY), m.above(ms(london, "2026-09-29T08:00"), null))
        assertEquals(emptyList<DayMarker?>(), m.forList(emptyList()))
    }

    // ---- Midnight ----

    @Test
    fun midnight_splitsTheDaysEitherSideOfIt() {
        val m = markersAt(london, LocalDateTime.parse("2026-09-30T12:00"))
        val lastOfYesterday = ms(london, "2026-09-29T23:59:59.999")
        val firstOfToday = ms(london, "2026-09-30T00:00")
        assertEquals(
            listOf(marker("2026-09-29", YESTERDAY), marker("2026-09-30", TODAY)),
            m.forList(listOf(lastOfYesterday, firstOfToday)),
        )
    }

    @Test
    fun midnight_bothEndsOfOneDayStayTogether() {
        val m = markersAt(london, LocalDateTime.parse("2026-09-30T23:59:59.999"))
        val list = listOf(ms(london, "2026-09-30T00:00"), ms(london, "2026-09-30T23:59:59.999"))
        assertEquals(listOf(marker("2026-09-30", TODAY), null), m.forList(list))
    }

    @Test
    fun midnight_theClockAtMidnightIsTheNewDay() {
        val justBefore = markersAt(london, LocalDateTime.parse("2026-09-29T23:59:59.999"))
        val atMidnight = markersAt(london, LocalDateTime.parse("2026-09-30T00:00"))
        val msg = ms(london, "2026-09-29T23:59")
        assertEquals(marker("2026-09-29", TODAY), justBefore.above(msg, null))
        assertEquals(marker("2026-09-29", YESTERDAY), atMidnight.above(msg, null))
    }

    @Test
    fun screenOpenAcrossMidnight_todayBecomesYesterdayOnTheNextRefresh() {
        val list = listOf(ms(london, "2026-09-29T22:00"), ms(london, "2026-09-29T23:30"))
        val before = markersAt(london, LocalDateTime.parse("2026-09-29T23:45")).forList(list)
        assertEquals(listOf(marker("2026-09-29", TODAY), null), before)
        // A message after midnight, and the rules made again as the screen does.
        val after = markersAt(london, LocalDateTime.parse("2026-09-30T00:05"))
            .forList(list + ms(london, "2026-09-30T00:01"))
        assertEquals(listOf(marker("2026-09-29", YESTERDAY), null, marker("2026-09-30", TODAY)), after)
    }

    // ---- Daylight saving ----

    @Test
    fun dstSpringForward_theShortDayIsOneDay() {
        // New York, 8 March 2026: 02:00 EST jumps to 03:00 EDT, a 23-hour day.
        val m = markersAt(newYork, LocalDateTime.parse("2026-03-09T12:00"))
        val list = listOf(
            ms(newYork, "2026-03-07T23:30"),
            ms(newYork, "2026-03-08T00:30"),
            ms(newYork, "2026-03-08T01:59"),
            ms(newYork, "2026-03-08T03:00"),
            ms(newYork, "2026-03-08T23:30"),
            ms(newYork, "2026-03-09T00:10"),
        )
        assertEquals(
            listOf(
                marker("2026-03-07", THIS_YEAR),
                marker("2026-03-08", YESTERDAY), null, null, null,
                marker("2026-03-09", TODAY),
            ),
            m.forList(list),
        )
    }

    @Test
    fun dstSpringForward_yesterdayIsTheCalendarDayNotTwentyFourHours() {
        // 00:30 on 9 March is only 23 hours after 00:30 on 8 March.
        val m = markersAt(newYork, LocalDateTime.parse("2026-03-09T00:30"))
        assertEquals(marker("2026-03-08", YESTERDAY), m.above(ms(newYork, "2026-03-08T00:30"), null))
        // And 23:30 on 7 March to 23:30 on 8 March is 23 hours, yet two days.
        assertNotNull(m.above(ms(newYork, "2026-03-08T23:30"), ms(newYork, "2026-03-07T23:30")))
    }

    @Test
    fun dstFallBack_theLongDayIsOneDay() {
        // London, 25 October 2026: 02:00 BST falls back to 01:00 GMT, a 25-hour day.
        val m = markersAt(london, LocalDateTime.parse("2026-10-25T23:55"))
        val firstOfDay = ms(london, "2026-10-25T00:10")
        val repeatedHourBst = LocalDateTime.parse("2026-10-25T01:30").atZone(london).withEarlierOffsetAtOverlap().toInstant().toEpochMilli()
        val repeatedHourGmt = LocalDateTime.parse("2026-10-25T01:30").atZone(london).withLaterOffsetAtOverlap().toInstant().toEpochMilli()
        val lastOfDay = ms(london, "2026-10-25T23:50") // 24 h 40 min after firstOfDay
        assertEquals(
            listOf(marker("2026-10-25", TODAY), null, null, null),
            m.forList(listOf(firstOfDay, repeatedHourBst, repeatedHourGmt, lastOfDay)),
        )
        // Neighbours more than 24 hours apart on that one day still share it.
        assertNull(m.above(lastOfDay, firstOfDay))
    }

    // ---- Year boundary ----

    @Test
    fun yearBoundary_newYearsDay() {
        val m = markersAt(london, LocalDateTime.parse("2027-01-01T00:05"))
        val list = listOf(
            ms(london, "2026-12-30T12:00"),
            ms(london, "2026-12-31T23:59"),
            ms(london, "2027-01-01T00:00"),
        )
        assertEquals(
            listOf(marker("2026-12-30", OTHER_YEAR), marker("2026-12-31", YESTERDAY), marker("2027-01-01", TODAY)),
            m.forList(list),
        )
    }

    @Test
    fun yearBoundary_newYearsEve() {
        val m = markersAt(london, LocalDateTime.parse("2026-12-31T12:00"))
        val list = listOf(ms(london, "2025-12-31T23:59:59.999"), ms(london, "2026-01-01T00:00"))
        assertEquals(listOf(marker("2025-12-31", OTHER_YEAR), marker("2026-01-01", THIS_YEAR)), m.forList(list))
    }

    @Test
    fun futureDays_areDatesNeverToday() {
        // A sender's clock running ahead: tomorrow is a date, and one in next year has its year.
        val midYear = markersAt(london, LocalDateTime.parse("2026-06-15T12:00"))
        assertEquals(THIS_YEAR, midYear.kindOf(LocalDate.parse("2026-06-16")))
        val newYearsEve = markersAt(london, LocalDateTime.parse("2026-12-31T12:00"))
        assertEquals(OTHER_YEAR, newYearsEve.kindOf(LocalDate.parse("2027-01-01")))
    }

    @Test
    fun theYearIsTheCalendars() {
        // 1 May and 30 September 2026 share an ISO year but not a Hijri one
        // (1 Muharram 1448 falls in June 2026).
        val hijri = { a: LocalDate, b: LocalDate ->
            HijrahDate.from(a).get(ChronoField.YEAR_OF_ERA) == HijrahDate.from(b).get(ChronoField.YEAR_OF_ERA)
        }
        val may = LocalDate.parse("2026-05-01")
        assertEquals(THIS_YEAR, markersAt(london, LocalDateTime.parse("2026-09-30T12:00")).kindOf(may))
        assertEquals(OTHER_YEAR, markersAt(london, LocalDateTime.parse("2026-09-30T12:00"), hijri).kindOf(may))
    }

    // ---- Time zones ----

    @Test
    fun timeZoneChange_theSameMessagesRegroupInTheNewZone() {
        val late = java.time.Instant.parse("2026-09-30T22:30:00Z").toEpochMilli()
        val early = java.time.Instant.parse("2026-10-01T01:30:00Z").toEpochMilli()
        val now = java.time.Instant.parse("2026-10-01T02:00:00Z")

        val inUtc = DayMarkers(Clock.fixed(now, utc))
        assertEquals(listOf(marker("2026-09-30", YESTERDAY), marker("2026-10-01", TODAY)), inUtc.forList(listOf(late, early)))

        // The same instants 7 hours behind: both on the afternoon of 30 September, which is today there.
        val inLosAngeles = DayMarkers(Clock.fixed(now, losAngeles))
        assertEquals(listOf(marker("2026-09-30", TODAY), null), inLosAngeles.forList(listOf(late, early)))

        // And 9 hours ahead: both on the morning of 1 October.
        val inTokyo = DayMarkers(Clock.fixed(now, tokyo))
        assertEquals(listOf(marker("2026-10-01", TODAY), null), inTokyo.forList(listOf(late, early)))
    }

    // ---- Display order ----

    @Test
    fun outOfOrderTimestamps_compareNeighboursAsShown() {
        val m = markersAt(london, LocalDateTime.parse("2026-09-30T15:00"))
        val list = listOf(
            ms(london, "2026-09-30T10:00"),
            ms(london, "2026-09-29T23:00"),
            ms(london, "2026-09-30T11:00"),
            ms(london, "2026-09-30T09:00"),
        )
        assertEquals(
            listOf(marker("2026-09-30", TODAY), marker("2026-09-29", YESTERDAY), marker("2026-09-30", TODAY), null),
            m.forList(list),
        )
    }

    @Test
    fun pagingPrepend_olderPageFromTheSameDayTakesOverTheFirstMarker() {
        val m = markersAt(london, LocalDateTime.parse("2026-09-30T15:00"))
        val page = listOf(ms(london, "2026-09-29T20:00"), ms(london, "2026-09-30T09:00"), ms(london, "2026-09-30T10:00"))
        val before = m.forList(page)
        assertEquals(listOf(marker("2026-09-29", YESTERDAY), marker("2026-09-30", TODAY), null), before)

        val older = listOf(ms(london, "2026-09-29T08:00"), ms(london, "2026-09-29T12:00"))
        val after = m.forList(older + page)
        assertEquals(listOf(marker("2026-09-29", YESTERDAY), null, null, marker("2026-09-30", TODAY), null), after)
        // Only the old first message changed: every marker below it is as before.
        assertEquals(before.drop(1), after.drop(older.size + 1))
    }

    @Test
    fun pagingPrepend_olderPageFromAnotherDayLeavesTheFirstMarker() {
        val m = markersAt(london, LocalDateTime.parse("2026-09-30T15:00"))
        val page = listOf(ms(london, "2026-09-29T20:00"), ms(london, "2026-09-30T09:00"))
        val after = m.forList(listOf(ms(london, "2026-09-27T10:00")) + page)
        assertEquals(listOf(marker("2026-09-27", THIS_YEAR)) + m.forList(page), after)
    }

    @Test
    fun newMessage_atTheBottomGetsAMarkerOnlyOnANewDay() {
        val m = markersAt(london, LocalDateTime.parse("2026-09-30T15:00"))
        val list = listOf(ms(london, "2026-09-29T20:00"), ms(london, "2026-09-30T09:00"))
        val before = m.forList(list)
        assertEquals(before + listOf(null), m.forList(list + ms(london, "2026-09-30T14:59")))
        val fromYesterday = listOf(ms(london, "2026-09-29T20:00"))
        assertEquals(
            m.forList(fromYesterday) + marker("2026-09-30", TODAY),
            m.forList(fromYesterday + ms(london, "2026-09-30T00:01")),
        )
    }

    @Test
    fun newestFirst_matchesTheListAsShown() {
        // The lists hold messages newest first under reverseLayout; item i is
        // shown just below item i + 1, and the last loaded item is on top.
        val m = markersAt(london, LocalDateTime.parse("2026-09-30T15:00"))
        val shown = listOf(
            ms(london, "2025-12-31T23:00"),
            ms(london, "2026-01-01T09:00"),
            ms(london, "2026-01-01T10:00"),
            ms(london, "2026-09-29T20:00"),
            ms(london, "2026-09-30T09:00"),
            ms(london, "2026-09-30T10:00"),
        )
        val newestFirst = shown.reversed()
        val viaNewestFirst = newestFirst.indices.map { i ->
            m.aboveNewestFirst(i, newestFirst.size) { newestFirst.getOrNull(it) }
        }
        assertEquals(m.forList(shown), viaNewestFirst.reversed())
        assertNull(m.aboveNewestFirst(newestFirst.size, newestFirst.size) { newestFirst.getOrNull(it) })
    }

    // ---- Words ----

    /** Labels from java.time in [locale], standing in for the device's ICU ones. */
    private class TestLabels(locale: Locale, override val today: String, override val yesterday: String) : DayLabels {
        private val dayMonth = DateTimeFormatter.ofPattern("EEEE d MMMM", locale)
        private val dayMonthYear = DateTimeFormatter.ofPattern("EEEE d MMMM uuuu", locale)
        override fun date(date: LocalDate, withYear: Boolean): String =
            (if (withYear) dayMonthYear else dayMonth).format(date)
    }

    @Test
    fun labels_pickTheWordingForEachKind() {
        val m = markersAt(london, LocalDateTime.parse("2026-09-30T15:00"))
        val english = TestLabels(Locale.UK, "Today", "Yesterday")
        val list = listOf(
            ms(london, "2025-12-31T12:00"),
            ms(london, "2026-09-27T12:00"),
            ms(london, "2026-09-29T12:00"),
            ms(london, "2026-09-30T12:00"),
        )
        assertEquals(
            listOf("Wednesday 31 December 2025", "Sunday 27 September", "Yesterday", "Today"),
            m.forList(list).map { it?.label(english) },
        )
        val text = DayMarkerText(m, english)
        assertEquals("Sunday 27 September", text.above(list[1], list[0]))
        assertNull(text.above(list[1], ms(london, "2026-09-27T08:00")))
    }

    @Test
    fun labels_comeInTheInjectedLocale() {
        val m = markersAt(london, LocalDateTime.parse("2026-09-30T15:00"))
        val french = TestLabels(Locale.FRANCE, "Aujourd’hui", "Hier")
        assertEquals("dimanche 27 septembre", m.above(ms(london, "2026-09-27T12:00"), null)?.label(french))
        assertEquals("mercredi 31 décembre 2025", m.above(ms(london, "2025-12-31T12:00"), null)?.label(french))
        assertEquals("Hier", m.above(ms(london, "2026-09-29T12:00"), null)?.label(french))
        assertEquals("Aujourd’hui", m.above(ms(london, "2026-09-30T12:00"), null)?.label(french))
    }
}
