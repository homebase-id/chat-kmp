package id.homebase.chat.event

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import co.touchlab.kermit.Logger
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.EventKit.EKEntityType
import platform.EventKit.EKEvent
import platform.EventKit.EKEventStore
import platform.EventKit.EKSpan
import platform.Foundation.NSDate
import platform.Foundation.NSDateComponents
import platform.Foundation.NSTimeZone
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.timeZoneWithName

private const val TAG = "CalendarLauncher.ios"

@Composable
actual fun rememberCalendarLauncher(): CalendarLauncher {
    return remember { IosCalendarLauncher() }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalUuidApi::class)
private class IosCalendarLauncher : CalendarLauncher {
    override fun addToCalendar(event: EventDescriptor, messageId: Uuid) {
        val store = EKEventStore()
        // Request access; permission prompt is gated by NSCalendarsUsageDescription
        // in the host app's Info.plist. The completion fires off-main, so log
        // and let the user retry if denied — there is no live UI to update from
        // here (this is invoked in fire-and-forget style from the detail dialog).
        store.requestAccessToEntityType(EKEntityType.EKEntityTypeEvent) { granted, error ->
            if (!granted) {
                Logger.w(tag = TAG) { "Calendar access denied: ${error?.localizedDescription}" }
                return@requestAccessToEntityType
            }
            saveEvent(store, event)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun saveEvent(store: EKEventStore, event: EventDescriptor) {
        val ekEvent = EKEvent.eventWithEventStore(store)
        ekEvent.setTitle(event.title)
        if (event.description.isNotBlank()) ekEvent.setNotes(event.description)
        event.locationText?.let { ekEvent.setLocation(it) }
        event.meetingUrl?.let { url ->
            ekEvent.setURL(platform.Foundation.NSURL(string = url))
        }
        ekEvent.setStartDate(NSDate.dateWithTimeIntervalSince1970(event.startUtcMs / 1000.0))
        val endMs = event.endUtcMs ?: (event.startUtcMs + 60L * 60L * 1000L)
        ekEvent.setEndDate(NSDate.dateWithTimeIntervalSince1970(endMs / 1000.0))
        NSTimeZone.timeZoneWithName(event.timezone)?.let { ekEvent.setTimeZone(it) }
        ekEvent.setCalendar(store.defaultCalendarForNewEvents)

        try {
            store.saveEvent(ekEvent, EKSpan.EKSpanThisEvent, true, null)
        } catch (t: Throwable) {
            Logger.e(tag = TAG, throwable = t) { "Failed to save EKEvent" }
        }
    }
}
