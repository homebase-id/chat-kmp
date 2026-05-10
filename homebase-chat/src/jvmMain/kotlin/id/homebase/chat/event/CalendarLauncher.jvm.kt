package id.homebase.chat.event

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import co.touchlab.kermit.Logger
import java.awt.Desktop
import java.io.File
import java.text.SimpleDateFormat
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

    /**
     * RFC-5545 compliant .ics for a single VEVENT. Date/time is emitted as
     * UTC ("Z" suffix) which all calendar apps accept regardless of the
     * descriptor's IANA zone — the wall-clock time the user picked is
     * preserved by the UTC milliseconds.
     */
    private fun buildIcs(event: EventDescriptor, messageId: Uuid): String {
        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val now = fmt.format(Date())
        val start = fmt.format(Date(event.startUtcMs))
        val end = fmt.format(Date(event.endUtcMs ?: (event.startUtcMs + 60L * 60L * 1000L)))
        val uid = "$messageId@homebase"

        return buildString {
            append("BEGIN:VCALENDAR\r\n")
            append("VERSION:2.0\r\n")
            append("PRODID:-//Homebase//Chat//EN\r\n")
            append("CALSCALE:GREGORIAN\r\n")
            append("BEGIN:VEVENT\r\n")
            append("UID:").append(uid).append("\r\n")
            append("DTSTAMP:").append(now).append("\r\n")
            append("DTSTART:").append(start).append("\r\n")
            append("DTEND:").append(end).append("\r\n")
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

    /** Escape per RFC-5545 §3.3.11: backslash, semicolon, comma, newlines. */
    private fun escapeIcs(s: String): String =
        s.replace("\\", "\\\\")
            .replace(";", "\\;")
            .replace(",", "\\,")
            .replace("\r\n", "\\n")
            .replace("\n", "\\n")
}
