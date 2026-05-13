package id.homebase.chat.event

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import co.touchlab.kermit.Logger
import java.awt.Desktop
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant as JInstant
import java.time.LocalDateTime as JLocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "CalendarLauncher.jvm"

@Composable
actual fun rememberCalendarLauncher(): CalendarLauncher {
    return remember { JvmCalendarLauncher() }
}

@OptIn(ExperimentalUuidApi::class)
private class JvmCalendarLauncher : CalendarLauncher {
    override fun addToCalendar(event: EventDescriptor, messageId: Uuid) {
        try {
            val ics = buildIcs(event, messageId)
            val tempFile = File.createTempFile("homebase_event_", ".ics")
            tempFile.writeText(ics, Charsets.UTF_8)
            tempFile.deleteOnExit()
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(tempFile)
            } else {
                Logger.w(tag = TAG) { "Desktop.open not supported on this platform" }
            }
        } catch (t: Throwable) {
            Logger.e(tag = TAG, throwable = t) { "Failed to generate / open .ics" }
        }
    }
}

/**
 * RFC-5545 compliant .ics for a single VEVENT.
 *
 * When `event.timezone` parses as a valid IANA zone we emit
 * `DTSTART;TZID=…:<local>` plus a `VTIMEZONE` block so importing
 * calendars preserve the **author's** wall-clock and zone label
 * (e.g. "Tue 09:00 JST" stays "JST" in Outlook even on a Berlin
 * machine). When the zone is unparseable we fall back to plain UTC
 * (`…Z` suffix) — the moment in time is still exact, just unlabeled.
 *
 * Single-instance events only: the VTIMEZONE block declares a single
 * STANDARD sub-component carrying the offset valid at the event
 * start. No DST transitions are encoded because none of our events
 * recur — they're single VEVENTs whose start/end are minutes to
 * hours apart, so they share one offset.
 *
 * `dtstampOverride` is for tests — production calls let it default
 * to `null` and stamps with `Date()`.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun buildIcs(
    event: EventDescriptor,
    messageId: Uuid,
    dtstampOverride: Date? = null,
): String {
    val utcFmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val dtstamp = utcFmt.format(dtstampOverride ?: Date())
    val endMs = event.endUtcMs ?: (event.startUtcMs + 60L * 60L * 1000L)
    val uid = "$messageId@homebase"

    val zoneId = runCatching { ZoneId.of(event.timezone) }.getOrNull()

    return buildString {
        append("BEGIN:VCALENDAR\r\n")
        append("VERSION:2.0\r\n")
        append("PRODID:-//Homebase//Chat//EN\r\n")
        append("CALSCALE:GREGORIAN\r\n")
        if (zoneId != null) {
            appendVTimezone(zoneId, JInstant.ofEpochMilli(event.startUtcMs))
        }
        append("BEGIN:VEVENT\r\n")
        append("UID:").append(uid).append("\r\n")
        append("DTSTAMP:").append(dtstamp).append("\r\n")
        if (zoneId != null) {
            append("DTSTART;TZID=").append(zoneId.id).append(":")
                .append(formatLocal(event.startUtcMs, zoneId)).append("\r\n")
            append("DTEND;TZID=").append(zoneId.id).append(":")
                .append(formatLocal(endMs, zoneId)).append("\r\n")
        } else {
            append("DTSTART:").append(utcFmt.format(Date(event.startUtcMs))).append("\r\n")
            append("DTEND:").append(utcFmt.format(Date(endMs))).append("\r\n")
        }
        append("SUMMARY:").append(escapeIcs(event.title)).append("\r\n")
        if (event.description.isNotBlank()) {
            append("DESCRIPTION:").append(escapeIcs(event.description)).append("\r\n")
        }
        event.locationText?.let { append("LOCATION:").append(escapeIcs(it)).append("\r\n") }
        event.meetingUrl?.let { append("URL:").append(it).append("\r\n") }
        append("END:VEVENT\r\n")
        append("END:VCALENDAR\r\n")
    }
}

private val localFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss", Locale.US)

private fun formatLocal(epochMs: Long, zone: ZoneId): String =
    JLocalDateTime.ofInstant(JInstant.ofEpochMilli(epochMs), zone).format(localFmt)

private fun StringBuilder.appendVTimezone(zone: ZoneId, at: JInstant) {
    val offsetSeconds = zone.rules.getOffset(at).totalSeconds
    val sign = if (offsetSeconds < 0) "-" else "+"
    val absMin = kotlin.math.abs(offsetSeconds) / 60
    val hh = (absMin / 60).toString().padStart(2, '0')
    val mm = (absMin % 60).toString().padStart(2, '0')
    val offset = "$sign$hh$mm"
    val tzName = zone.id.substringAfterLast('/').replace('_', ' ')
    append("BEGIN:VTIMEZONE\r\n")
    append("TZID:").append(zone.id).append("\r\n")
    append("BEGIN:STANDARD\r\n")
    append("DTSTART:19700101T000000\r\n")
    append("TZOFFSETFROM:").append(offset).append("\r\n")
    append("TZOFFSETTO:").append(offset).append("\r\n")
    append("TZNAME:").append(tzName).append("\r\n")
    append("END:STANDARD\r\n")
    append("END:VTIMEZONE\r\n")
}

/** Escape per RFC-5545 §3.3.11: backslash, semicolon, comma, newlines. */
private fun escapeIcs(s: String): String =
    s.replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
