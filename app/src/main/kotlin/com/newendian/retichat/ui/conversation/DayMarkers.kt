package com.newendian.retichat.ui.conversation

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The date marker drawn above a message in a message list: [date] is the
 * calendar day the message was sent on, [kind] which wording it takes.
 */
data class DayMarker(val date: LocalDate, val kind: Kind) {
    enum class Kind { TODAY, YESTERDAY, THIS_YEAR, OTHER_YEAR }
}

/**
 * The words for a marker in the viewer's locale. The device's come from the
 * platform's ICU formatters (IcuDayLabels); tests pass their own.
 */
interface DayLabels {
    val today: String
    val yesterday: String

    /** [date] as weekday, day and month, and the year too when [withYear]. */
    fun date(date: LocalDate, withYear: Boolean): String
}

/** What [labels] say for this marker. */
fun DayMarker.label(labels: DayLabels): String = when (kind) {
    DayMarker.Kind.TODAY -> labels.today
    DayMarker.Kind.YESTERDAY -> labels.yesterday
    DayMarker.Kind.THIS_YEAR -> labels.date(date, withYear = false)
    DayMarker.Kind.OTHER_YEAR -> labels.date(date, withYear = true)
}

/**
 * Which messages in a list get a date marker above them, and which wording.
 *
 * A message gets one when its calendar day differs from the day of the
 * message shown directly above it, and the first (topmost) message of the
 * loaded list always gets one. Days are compared in display order, so
 * out-of-order timestamps still get a marker wherever the day changes.
 *
 * Pure: [today], the time zone and the calendar's idea of a year are given,
 * so the rules are tested on the JVM (DayMarkersTest). [today] is fixed when
 * this is made; the screen makes a new one when the date, clock or time zone
 * changes, which turns yesterday's "Today" into "Yesterday".
 *
 * [sameYear] says whether two days fall in the same year of the viewer's
 * calendar (ISO by default; the device's comes from ICU, IcuDayLabels).
 */
class DayMarkers(
    val today: LocalDate,
    val zone: ZoneId,
    private val sameYear: (LocalDate, LocalDate) -> Boolean = ISO_YEARS,
) {
    constructor(
        clock: Clock,
        sameYear: (LocalDate, LocalDate) -> Boolean = ISO_YEARS,
    ) : this(LocalDate.now(clock), clock.zone, sameYear)

    /** The calendar day [timestampMillis] falls on in [zone]. */
    fun dayOf(timestampMillis: Long): LocalDate =
        Instant.ofEpochMilli(timestampMillis).atZone(zone).toLocalDate()

    /**
     * The marker above the message sent at [timestampMillis], whose neighbour
     * shown directly above it was sent at [aboveMillis] (null when it is the
     * first message of the loaded list). Null when both fall on one day.
     */
    fun above(timestampMillis: Long, aboveMillis: Long?): DayMarker? {
        val day = dayOf(timestampMillis)
        if (aboveMillis != null && dayOf(aboveMillis) == day) return null
        return DayMarker(day, kindOf(day))
    }

    /** The markers of a whole list, [timestamps] in display order, top to bottom. */
    fun forList(timestamps: List<Long>): List<DayMarker?> =
        timestamps.mapIndexed { i, ts -> above(ts, timestamps.getOrNull(i - 1)) }

    /**
     * The marker above item [index] of a newest-first list of [count] loaded
     * items, as a reverseLayout list holds them: the message shown directly
     * above item i is item i + 1, and the last loaded item is the first shown.
     * [timestampAt] reads an item's timestamp (null: no item there).
     */
    fun aboveNewestFirst(index: Int, count: Int, timestampAt: (Int) -> Long?): DayMarker? {
        val timestamp = timestampAt(index) ?: return null
        val aboveMillis = if (index + 1 < count) timestampAt(index + 1) else null
        return above(timestamp, aboveMillis)
    }

    /** Which wording a marker for [day] takes, as of [today]. */
    fun kindOf(day: LocalDate): DayMarker.Kind = when {
        day == today -> DayMarker.Kind.TODAY
        day == today.minusDays(1) -> DayMarker.Kind.YESTERDAY
        sameYear(day, today) -> DayMarker.Kind.THIS_YEAR
        else -> DayMarker.Kind.OTHER_YEAR
    }

    companion object {
        /** Years of the ISO (Gregorian) calendar. */
        val ISO_YEARS: (LocalDate, LocalDate) -> Boolean = { a, b -> a.year == b.year }
    }
}

/** The rules and the words together: what a message list draws above a message. */
class DayMarkerText(val markers: DayMarkers, private val labels: DayLabels) {
    /** The zone the markers' days are in; the bubbles' times are shown in it too. */
    val zone: ZoneId get() = markers.zone

    /** The marker text above a message (see [DayMarkers.above]), or null for none. */
    fun above(timestampMillis: Long, aboveMillis: Long?): String? =
        markers.above(timestampMillis, aboveMillis)?.label(labels)

    /** The marker text above item [index] of a newest-first list (see [DayMarkers.aboveNewestFirst]). */
    fun aboveNewestFirst(index: Int, count: Int, timestampAt: (Int) -> Long?): String? =
        markers.aboveNewestFirst(index, count, timestampAt)?.label(labels)
}
