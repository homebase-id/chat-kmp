package id.homebase.feed.receivers

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import co.touchlab.kermit.Logger
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.core.notifications.RichNotificationDisplayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Handles "Mark as Read" notification action. Sends read receipts
 * via ChatMessageActionService and dismisses the notification.
 */
class NotificationMarkReadReceiver : BroadcastReceiver(), KoinComponent {

    private val chatMessageActionService: ChatMessageActionService by inject()

    @OptIn(ExperimentalUuidApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getStringExtra(RichNotificationDisplayer.EXTRA_CONVERSATION_ID)
            ?: return
        val notificationId = intent.getIntExtra(RichNotificationDisplayer.EXTRA_NOTIFICATION_ID, 0)

        Logger.i(tag = "NotificationMarkRead") {
            "Marking conversation as read: $conversationId"
        }

        // Dismiss notification immediately
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(notificationId)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val convoUuid = Uuid.parse(conversationId)
                chatMessageActionService.markAsReadLatestFileCreated(convoUuid, emptyList())
            } catch (e: Exception) {
                Logger.e(tag = "NotificationMarkRead") {
                    "Failed to mark as read: ${e.message}"
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
