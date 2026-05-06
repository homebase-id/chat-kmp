package id.homebase.chat.event

import androidx.compose.runtime.Composable

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
 */
interface CalendarLauncher {
    fun addToCalendar(event: EventDescriptor)
}

@Composable
expect fun rememberCalendarLauncher(): CalendarLauncher
