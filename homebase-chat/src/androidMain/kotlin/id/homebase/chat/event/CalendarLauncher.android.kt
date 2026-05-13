package id.homebase.chat.event

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import co.touchlab.kermit.Logger
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "CalendarLauncher"

@Composable
actual fun rememberCalendarLauncher(): CalendarLauncher {
    val context = LocalContext.current
    return remember(context) { AndroidCalendarLauncher(context.applicationContext) }
}

@OptIn(ExperimentalUuidApi::class)
private class AndroidCalendarLauncher(private val appContext: Context) : CalendarLauncher {
    override fun addToCalendar(event: EventDescriptor, messageId: Uuid) {
        val intent = Intent(Intent.ACTION_INSERT).apply {
            data = CalendarContract.Events.CONTENT_URI
            putExtra(CalendarContract.Events.TITLE, event.title)
            putExtra(
                CalendarContract.Events.DESCRIPTION,
                composeCalendarDescription(event.description, event.meetingUrl),
            )
            putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.startUtcMs)
            event.endUtcMs?.let { putExtra(CalendarContract.EXTRA_EVENT_END_TIME, it) }
            putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, false)
            putExtra(CalendarContract.Events.EVENT_TIMEZONE, event.timezone)
            event.locationText?.let { putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
            // Started from a non-Activity context — needs NEW_TASK.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            appContext.startActivity(intent)
        } catch (t: Throwable) {
            Logger.e(tag = TAG, throwable = t) { "Failed to launch ACTION_INSERT" }
        }
    }
}
