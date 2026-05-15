package id.homebase.chat.event

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

// Web actual: no native calendar API, but the chat UI's "Add to calendar"
// detail dialog already offers a separate Google Calendar action that opens
// `googleCalendarUrl(event)` via the URI handler — so this launcher can be a
// no-op without losing functionality.
@Composable
actual fun rememberCalendarLauncher(): CalendarLauncher = remember {
    object : CalendarLauncher {
        @OptIn(ExperimentalUuidApi::class)
        override fun addToCalendar(event: EventDescriptor, messageId: Uuid) {
            // No-op: use googleCalendarUrl(event) via the URI handler instead.
        }
    }
}
