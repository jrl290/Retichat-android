package com.newendian.retichat.ui.conversation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.icu.text.DateFormat
import android.icu.text.DisplayContext
import android.icu.text.RelativeDateTimeFormatter
import android.icu.util.Calendar
import android.icu.util.ULocale
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/**
 * A message list item: its date marker, when it has one, above it. The marker
 * lives inside the message's own item, so it adds no list item: nothing that
 * counts, keys, scrolls to or selects messages ever sees it.
 */
@Composable
fun WithDayMarker(marker: String?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (marker != null) DayMarkerRow(marker)
        content()
    }
}

/** A date marker: small, centred, secondary text between messages. Not a message; nothing taps it. */
@Composable
fun DayMarkerRow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .semantics { heading() },
    )
}

/**
 * The [DayMarkerText] for a message list on screen, in the device's time
 * zone, locale and calendar. It is made again when the day turns (the
 * platform's date-changed broadcast), when the clock or time zone is changed,
 * when the screen comes back to the front (a change missed while away), and
 * when the locale changes; that is when today's "Today" becomes "Yesterday".
 * Otherwise it stays the same object, so the list does not recompose for it.
 */
@Composable
fun rememberDayMarkerText(): DayMarkerText {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var day by remember { mutableStateOf(DeviceDay.now()) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                day = DeviceDay.now()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) day = DeviceDay.now()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return remember(day, locale) {
        val labels = IcuDayLabels(locale, day.zone, day.today)
        DayMarkerText(DayMarkers(day.today, day.zone, labels::sameYear), labels)
    }
}

/** Today and the time zone, as the device has them now. Equal values do not recompose. */
private data class DeviceDay(val today: LocalDate, val zone: ZoneId) {
    companion object {
        fun now(): DeviceDay {
            val zone = ZoneId.systemDefault()
            return DeviceDay(LocalDate.now(zone), zone)
        }
    }
}

/**
 * [DayLabels] from the platform's ICU formatters, in [locale] and that
 * locale's calendar: "Today" and "Yesterday" as the relative-date formatter
 * words them, and dates from the locale's own pattern for weekday, day and
 * month (and year). [today] only stands in should a locale lack those words.
 */
class IcuDayLabels(locale: Locale, private val zone: ZoneId, today: LocalDate) : DayLabels {
    private val uLocale = ULocale.forLocale(locale)
    private val icuZone = android.icu.util.TimeZone.getTimeZone(zone.id)
    private val dayMonth = formatter("EEEEdMMMM")
    private val dayMonthYear = formatter("EEEEdMMMMy")
    private val calendar = Calendar.getInstance(icuZone, uLocale)
    private val relative = RelativeDateTimeFormatter.getInstance(
        uLocale,
        null,
        RelativeDateTimeFormatter.Style.LONG,
        DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE,
    )

    override val today: String =
        relativeDay(RelativeDateTimeFormatter.Direction.THIS) ?: date(today, withYear = false)
    override val yesterday: String =
        relativeDay(RelativeDateTimeFormatter.Direction.LAST) ?: date(today.minusDays(1), withYear = false)

    override fun date(date: LocalDate, withYear: Boolean): String =
        (if (withYear) dayMonthYear else dayMonth).format(Date(noon(date)))

    /** Whether [a] and [b] fall in one year (era and year) of the locale's calendar. */
    fun sameYear(a: LocalDate, b: LocalDate): Boolean {
        calendar.timeInMillis = noon(a)
        val era = calendar.get(Calendar.ERA)
        val year = calendar.get(Calendar.YEAR)
        calendar.timeInMillis = noon(b)
        return calendar.get(Calendar.ERA) == era && calendar.get(Calendar.YEAR) == year
    }

    private fun relativeDay(direction: RelativeDateTimeFormatter.Direction): String? =
        relative.format(direction, RelativeDateTimeFormatter.AbsoluteUnit.DAY)?.takeIf { it.isNotBlank() }

    private fun formatter(skeleton: String): DateFormat =
        DateFormat.getInstanceForSkeleton(skeleton, uLocale).apply {
            timeZone = icuZone
            setContext(DisplayContext.CAPITALIZATION_FOR_BEGINNING_OF_SENTENCE)
        }

    /** Noon of [date] in [zone]: inside that day whatever its daylight-saving change. */
    private fun noon(date: LocalDate): Long =
        date.atTime(LocalTime.NOON).atZone(zone).toInstant().toEpochMilli()
}
