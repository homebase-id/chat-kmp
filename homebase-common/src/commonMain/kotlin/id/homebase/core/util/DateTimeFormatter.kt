package id.homebase.core.util

import kotlin.time.Instant

/** [instant] as the locale's short numeric date in the device time zone, e.g. "5/8/26" or "08.05.2026". Never blank. */
expect fun formatShortDate(instant: Instant): String

/** [instant] as the locale's short time in the device time zone, e.g. "3:07 PM" or "15.07". Never blank. */
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