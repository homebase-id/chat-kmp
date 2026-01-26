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
import id.homebase.resources.time_yesterday
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant


@Composable
fun formatTimestamp(timestamp: Instant): String {
    val now = Clock.System.now()
    val diff = now - timestamp

    return when {
        diff < 1.minutes -> stringResource(MR.string.time_just_now)
        diff < 1.hours -> stringResource(MR.string.time_minutes_ago, diff.inWholeMinutes)
        diff < 24.hours -> formatTime(timestamp) //stringResource(MR.string.time_hours_ago, diff.inWholeHours)
        diff < 2.days -> stringResource(MR.string.time_yesterday)
        diff < 7.days -> {
            val localDateTime = timestamp.toLocalDateTime(TimeZone.currentSystemDefault())
            getDayName(localDateTime.dayOfWeek.ordinal)
        }
        else -> formatShortDate(timestamp)
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

