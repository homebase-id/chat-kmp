package id.homebase.feed.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.touchlab.kermit.Logger
import id.homebase.core.NotificationActionBridge
import id.homebase.core.notifications.RichNotificationDisplayer
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** "Mark as read" notification action: runs the same path as iOS through [NotificationActionBridge]. */
class NotificationMarkReadReceiver : BroadcastReceiver(), KoinComponent {

    private val bridge: NotificationActionBridge by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getStringExtra(RichNotificationDisplayer.EXTRA_CONVERSATION_ID)
            ?: return

        Logger.i(tag = "NotificationMarkRead") { "Marking conversation as read: $conversationId" }

        val pendingResult = goAsync()
        bridge.markAsRead(conversationId) { pendingResult.finish() }
    }
}
