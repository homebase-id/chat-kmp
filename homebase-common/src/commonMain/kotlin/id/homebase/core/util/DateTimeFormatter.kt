package id.homebase.core.util

import kotlin.time.Instant

expect fun formatShortDate(instant: Instant): String
expect fun formatTime(instant: Instant): String

/**
 * Locale-aware medium date, e.g. "May 8, 2026" / "8 May 2026" / "8. Mai 2026".
 * Use for absolute dates in feed cards / lists where year is meaningful.
 */
expect fun formatMediumDate(instant: Instant): String

/**
 * Locale-aware month + day with no year, e.g. "May 8" / "8 May" / "8. Mai".
 * Use when the year is implied (current year). All platforms honor the locale's day/month ordering.
 */
expect fun formatMediumDateNoYear(instant: Instant): String

fun formateDateTime(instant: Instant): String {
    return formatShortDate(instant) + " " + formatTime(instant)
}