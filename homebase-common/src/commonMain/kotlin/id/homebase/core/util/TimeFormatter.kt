package id.homebase.core.util

import androidx.compose.runtime.Composable
import id.homebase.resources.MR
import id.homebase.resources.day_friday
import id.homebase.resources.day_monday
import id.homebase.resources.day_saturday
import id.homebase.resources.day_sunday
import id.homebase.resources.day_thursday
import id.homebase.resources.day_tuesday
import id.homebase.resources.day_wednesday
import id.homebase.resources.time_just_now
import id.homebase.resources.time_minutes_ago
import id.homebase.resources.time_today
import id.homebase.resources.time_yesterday
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant


@Composable
fun formatTimestamp(timestamp: Instant): String {
    val now = Clock.System.now()
    val diff = now - timestamp

    val nowDate = now.toLocalDateTime(TimeZone.currentSystemDefault()).date
    val timestampDate = timestamp.toLocalDateTime(TimeZone.currentSystemDefault()).date
    val daysDiff = (nowDate.toEpochDays() - timestampDate.toEpochDays())

    return when {
        diff < 1.minutes -> stringResource(MR.string.time_just_now)
        diff < 1.hours -> stringResource(MR.string.time_minutes_ago, diff.inWholeMinutes)
        daysDiff == 0L -> formatTime(timestamp) // Today
        daysDiff == 1L -> stringResource(MR.string.time_yesterday) // Yesterday
        daysDiff < 7 -> {
            val localDateTime = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
            getDayName(localDateTime.dayOfWeek.ordinal)
        }
        else -> formatShortDate(timestamp)
    }
}

/**
 * "Date-of-the-moment" formatter for feed cards. Resolves to:
 *  - "Today"           if the timestamp lands on the user's current local day
 *  - "Yesterday"       one day prior
 *  - "May 8" / "8 Mai" same calendar year, locale-ordered no-year medium
 *  - "May 8, 2024"     other years, full locale-medium date
 *
 * Distinct from [formatTimestamp] (which is recency-focused: minutes-ago,
 * day-of-week, etc.). Use this when the date itself is the headline.
 */
@Composable
fun formatMomentDate(timestamp: Instant): String {
    val zone = TimeZone.currentSystemDefault()
    val nowDate = Clock.System.now().toLocalDateTime(zone).date
    val tsDate = timestamp.toLocalDateTime(zone).date
    val daysDiff = nowDate.toEpochDays() - tsDate.toEpochDays()

    return when {
        daysDiff == 0L -> stringResource(MR.string.time_today)
        daysDiff == 1L -> stringResource(MR.string.time_yesterday)
        nowDate.year == tsDate.year -> formatMediumDateNoYear(timestamp)
        else -> formatMediumDate(timestamp)
    }
}

@Composable
fun formatMessageTimestamp(timestamp: Instant): String {
    val now = Clock.System.now()
    val diff = now - timestamp

    return when {
        diff < 1.minutes -> stringResource(MR.string.time_just_now)
        diff < 1.hours -> stringResource(MR.string.time_minutes_ago, diff.inWholeMinutes)
       else -> formatTime(timestamp)
    }
}

@Composable
private fun getDayName(dayOfWeek: Int): String {
    return when (dayOfWeek) {
        0 -> stringResource(MR.string.day_monday)
        1 -> stringResource(MR.string.day_tuesday)
        2 -> stringResource(MR.string.day_wednesday)
        3 -> stringResource(MR.string.day_thursday)
        4 -> stringResource(MR.string.day_friday)
        5 -> stringResource(MR.string.day_saturday)
        6 -> stringResource(MR.string.day_sunday)
        else -> ""
    }
}

