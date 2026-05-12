package id.homebase.chat.event

import androidx.compose.runtime.Composable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.datetime.toLocalDateTime

/**
 * Platform-specific entry to add an event to the user's calendar.
 *
 * Android   — fires `Intent.ACTION_INSERT` on `CalendarContract.Events.CONTENT_URI`,
 *             letting the system calendar app pre-fill its create-event UI. No
 *             permission required (the picker handles it).
 * iOS       — uses EventKit (`EKEventStore.requestAccess` + `saveEvent`). The host
 *             app must declare `NSCalendarsUsageDescription` in `Info.plist`.
 * Desktop   — writes a one-event `.ics` file to a temp directory and opens it
 *             with the OS default handler. Outlook / Apple Calendar / GNOME
 *             Calendar all import.
 *
 * Note: this is the **native** path. Google Calendar (web) users on Desktop
 * have no `.ics` association — the detail dialog also offers a separate
 * "Add to Google Calendar" action that opens the template URL in the
 * browser. See [googleCalendarUrl] below.
 */
interface CalendarLauncher {
    @OptIn(ExperimentalUuidApi::class)
    fun addToCalendar(event: EventDescriptor, messageId: Uuid)
}

@Composable
expect fun rememberCalendarLauncher(): CalendarLauncher

/**
 * Google Calendar create-event template URL. Opens in the user's browser via
 * `LocalUriHandler.openUri`. Pre-fills title, description, location, dates,
 * and the source IANA timezone.
 *
 * Uses the documented `calendar.google.com/calendar/render?action=TEMPLATE`
 * endpoint. Dates are emitted in compact UTC (`YYYYMMDDTHHMMSSZ`) which
 * Google Calendar parses regardless of the `ctz` parameter — the `ctz` is
 * a display hint so the create-event form opens with the right zone
 * pre-selected.
 *
 * End time defaults to start + 1 hour when [EventDescriptor.endUtcMs] is
 * null, mirroring the .ics fallback we already use elsewhere.
 */
fun googleCalendarUrl(event: EventDescriptor): String {
    val endMs = event.endUtcMs ?: (event.startUtcMs + 60L * 60L * 1000L)
    val details = composeCalendarDescription(event.description, event.meetingUrl)
    val params = listOfNotNull(
        "action" to "TEMPLATE",
        "text" to event.title,
        "dates" to "${formatGcalUtc(event.startUtcMs)}/${formatGcalUtc(endMs)}",
        details.takeIf { it.isNotBlank() }?.let { "details" to it },
        event.locationText?.takeIf { it.isNotBlank() }?.let { "location" to it },
        "ctz" to event.timezone,
    ).joinToString("&") { (k, v) -> "$k=${urlEncode(v)}" }
    return "https://calendar.google.com/calendar/render?$params"
}

/**
 * Build the description text for calendar paths that have no dedicated meeting
 * URL field (Google Calendar's `?action=TEMPLATE` URL, Android's
 * `Intent.ACTION_INSERT`). Prepends a `Join: <url>` line so the URL travels
 * with the event and stays clickable inside the calendar app.
 *
 * `.ics` and iOS EventKit do not use this — they have native `URL:` /
 * `EKEvent.setURL` fields and would just duplicate the URL.
 *
 * Idempotent: if [description] already contains [meetingUrl] verbatim
 * (e.g. the author pasted it manually) we don't add a second copy.
 */
internal fun composeCalendarDescription(description: String, meetingUrl: String?): String {
    val url = meetingUrl?.takeIf { it.isNotBlank() } ?: return description
    if (description.contains(url)) return description
    return if (description.isBlank()) "Join: $url" else "Join: $url\n\n$description"
}

/** Format epoch milliseconds as Google Calendar's compact UTC: `YYYYMMDDTHHMMSSZ`. */
internal fun formatGcalUtc(epochMs: Long): String {
    // ISO format from LocalDateTime is "YYYY-MM-DDTHH:MM:SS" (or with fractional seconds).
    // Strip separators and append Z. Trim any sub-second precision by cutting at the dot.
    val iso = kotlin.time.Instant.fromEpochMilliseconds(epochMs)
        .toLocalDateTime(kotlinx.datetime.TimeZone.UTC)
        .toString()
    val clean = iso.substringBefore('.')
    val padded = if (clean.length == 16) "$clean:00" else clean // append seconds when missing
    return padded.replace("-", "").replace(":", "") + "Z"
}

internal fun urlEncode(s: String): String = buildString(s.length) {
    for (c in s) {
        when {
            c.isLetterOrDigit() || c == '-' || c == '_' || c == '.' || c == '~' -> append(c)
            c == ' ' -> append('+')
            else -> for (b in c.toString().encodeToByteArray()) {
                append('%')
                append(((b.toInt() shr 4) and 0xF).toString(16).uppercase())
                append((b.toInt() and 0xF).toString(16).uppercase())
            }
        }
    }
}
