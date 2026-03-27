package id.homebase.feed.receivers

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import co.touchlab.kermit.Logger
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.core.notifications.RichNotificationDisplayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Handles direct reply from notification. Extracts reply text from RemoteInput
 * and sends a message via ChatMessageSenderService.
 */
class NotificationReplyReceiver : BroadcastReceiver(), KoinComponent {

    private val chatMessageSenderService: ChatMessageSenderService by inject()

    @OptIn(ExperimentalUuidApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getStringExtra(RichNotificationDisplayer.EXTRA_CONVERSATION_ID)
            ?: return
        val notificationId = intent.getIntExtra(RichNotificationDisplayer.EXTRA_NOTIFICATION_ID, 0)

        val remoteInputResults = RemoteInput.getResultsFromIntent(intent) ?: return
        val replyText =
            remoteInputResults.getCharSequence(RichNotificationDisplayer.EXTRA_REPLY_TEXT)
                ?.toString()
                ?.trim()

        if (replyText.isNullOrEmpty()) return

        Logger.i(tag = "NotificationReply") {
            "Replying to conversation $conversationId: $replyText"
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val convoUuid = Uuid.parse(conversationId)
                val messageUuid = Uuid.random()
                chatMessageSenderService.sendNewMessage(
                    messageUniqueId = messageUuid,
                    conversationId = convoUuid,
                    messageText = replyText,
                    previousMessageUniqueId = null,
                    payloadBundle = null,
                )

                // Dismiss the notification after successful reply
                val nm =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(notificationId)
            } catch (e: Exception) {
                Logger.e(tag = "NotificationReply") {
                    "Failed to send reply: ${e.message}"
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
