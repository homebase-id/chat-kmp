package id.homebase.notifshared

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Wire-format sentinel that the chat sender writes into
 * `PushNotificationOptions.unEncryptedMessage` for Event messages instead of
 * the event title. Followed by the event start time in UTC milliseconds,
 * e.g. `__hb_event__|1715716200000`.
 *
 * In a group chat, the ChatMessageSenderService wraps the sender-side
 * notification text as `"$text in $groupName"`, so the full wire payload
 * looks like `__hb_event__|1715716200000 in Family Group`. The recipient
 * splits the suffix back off and re-appends it after formatting.
 *
 * Receivers that don't recognize the sentinel will render it verbatim
 * (no title — only a Unix timestamp leaks, which is acceptable for the
 * old-client migration window).
 */
const val EVENT_NOTIF_SENTINEL = "__hb_event__|"

data class ParsedEventNotif(
    val startUtcMs: Long,
    /** The "Family Group" part of `__hb_event__|... in Family Group`, or null in 1:1 chats. */
    val groupSuffix: String?,
)

private val EVENT_TOKEN_REGEX = Regex("""^__hb_event__\|(\d+)( in (.+))?$""")

fun parseEventNotificationToken(raw: String): ParsedEventNotif? {
    val match = EVENT_TOKEN_REGEX.matchEntire(raw) ?: return null
    val startUtcMs = match.groupValues[1].toLongOrNull() ?: return null
    val groupSuffix = match.groupValues[3].ifEmpty { null }
    return ParsedEventNotif(startUtcMs, groupSuffix)
}

/**
 * Privacy-safe notification body for an incoming Event. The recipient's
 * clock and timezone drive the relative-time phrasing — the title /
 * description / location never travel over the wire.
 *
 * Branches:
 *   - started > 1h ago             → "Event on <day>, <month> <d>"
 *   - within 1h of start (±)       → "Event starting now"
 *   - 1–59 min until start         → "Event in N minutes"
 *   - 1–23 hours until start       → "Event in N hours" (rounded)
 *   - ≥ 24h away                   → "Event on <day>, <month> <d>"
 *
 * [dayName] / [monthName] let callers inject localized weekday / month
 * names. The default lookups use English short names.
 */
fun formatEventNotificationBody(
    startUtcMs: Long,
    nowMs: Long,
    viewerTz: TimeZone,
    dayName: (DayOfWeek) -> String = ::defaultDayName,
    monthName: (Month) -> String = ::defaultMonthName,
): String {
    val delta = startUtcMs - nowMs
    val oneMinute = 60_000L
    val oneHour = 60L * oneMinute
    val oneDay = 24L * oneHour

    if (delta < -oneHour) {
        return "Event on ${formatAbsoluteDate(startUtcMs, viewerTz, dayName, monthName)}"
    }
    if (delta < oneMinute) {
        return "Event starting now"
    }
    if (delta < oneHour) {
        val minutes = (delta / oneMinute).toInt()
        return if (minutes == 1) "Event in 1 minute" else "Event in $minutes minutes"
    }
    if (delta < oneDay) {
        val hours = ((delta + oneHour / 2) / oneHour).toInt().coerceIn(1, 23)
        return if (hours == 1) "Event in 1 hour" else "Event in $hours hours"
    }
    return "Event on ${formatAbsoluteDate(startUtcMs, viewerTz, dayName, monthName)}"
}

private fun formatAbsoluteDate(
    startUtcMs: Long,
    viewerTz: TimeZone,
    dayName: (DayOfWeek) -> String,
    monthName: (Month) -> String,
): String {
    val localDateTime = Instant.fromEpochMilliseconds(startUtcMs).toLocalDateTime(viewerTz)
    val date = localDateTime.date
    return "${dayName(date.dayOfWeek)}, ${monthName(date.month)} ${date.day}"
}

private fun defaultDayName(d: DayOfWeek): String = when (d) {
    DayOfWeek.MONDAY -> "Mon"
    DayOfWeek.TUESDAY -> "Tue"
    DayOfWeek.WEDNESDAY -> "Wed"
    DayOfWeek.THURSDAY -> "Thu"
    DayOfWeek.FRIDAY -> "Fri"
    DayOfWeek.SATURDAY -> "Sat"
    DayOfWeek.SUNDAY -> "Sun"
}

private fun defaultMonthName(m: Month): String = when (m) {
    Month.JANUARY -> "Jan"
    Month.FEBRUARY -> "Feb"
    Month.MARCH -> "Mar"
    Month.APRIL -> "Apr"
    Month.MAY -> "May"
    Month.JUNE -> "Jun"
    Month.JULY -> "Jul"
    Month.AUGUST -> "Aug"
    Month.SEPTEMBER -> "Sep"
    Month.OCTOBER -> "Oct"
    Month.NOVEMBER -> "Nov"
    Month.DECEMBER -> "Dec"
}

/**
 * Top-level convenience that combines parsing, formatting, and group-suffix
 * re-append. Returns null if [raw] is not an event token — caller falls back
 * to its existing body-formation logic.
 *
 * Exposed primarily for the iOS NotificationServiceExtension, which links
 * this module as `HomebaseNotifKit.framework` and can't easily compose the
 * pieces from Swift.
 */
fun tryFormatEventNotificationBody(
    raw: String,
    nowMs: Long,
    viewerTz: TimeZone,
    dayName: (DayOfWeek) -> String = ::defaultDayName,
    monthName: (Month) -> String = ::defaultMonthName,
): String? {
    val parsed = parseEventNotificationToken(raw) ?: return null
    val body = formatEventNotificationBody(parsed.startUtcMs, nowMs, viewerTz, dayName, monthName)
    return if (parsed.groupSuffix != null) "$body in ${parsed.groupSuffix}" else body
}

/**
 * Zero-arg convenience — uses the system clock and the device's current
 * timezone. Primarily for callers that can't easily reach
 * `Clock.System.now()` / `TimeZone.currentSystemDefault()` themselves
 * (notably the iOS NotificationServiceExtension calling through
 * Kotlin/Native interop from Swift).
 */
fun tryFormatEventNotificationBodyNow(raw: String): String? =
    tryFormatEventNotificationBody(
        raw = raw,
        nowMs = Clock.System.now().toEpochMilliseconds(),
        viewerTz = TimeZone.currentSystemDefault(),
    )
