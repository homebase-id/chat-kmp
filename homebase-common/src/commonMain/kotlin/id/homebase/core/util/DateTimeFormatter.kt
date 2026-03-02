package id.homebase.core.util

import kotlin.time.Instant

expect fun formatShortDate(instant: Instant): String
expect fun formatTime(instant: Instant): String

fun formateDateTime(instant: Instant): String {
    return formatShortDate(instant) + " " + formatTime(instant)
}