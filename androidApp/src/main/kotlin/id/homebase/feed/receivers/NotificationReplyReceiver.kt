package id.homebase.feed.receivers

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import co.touchlab.kermit.Logger
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.core.localization.TranslationUtil
import id.homebase.core.notifications.RichNotificationDisplayer
import id.homebase.feed.R
import id.homebase.resources.MR
import id.homebase.resources.notification_reply_failed_hint
import id.homebase.resources.notification_reply_failed_title
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

        Logger.i(tag = TAG) { "Replying to conversation $conversationId" }

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
                Logger.e(tag = TAG, throwable = e) { "Failed to send reply" }
                // The typed text only exists here — a cold wake with a locked identity
                // would otherwise drop it silently.
                postReplyFailedNotification(context, conversationId, replyText)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun postReplyFailedNotification(
        context: Context,
        conversationId: String,
        replyText: String,
    ) {
        try {
            val title = TranslationUtil.getString(MR.string.notification_reply_failed_title)
            val hint = TranslationUtil.getString(MR.string.notification_reply_failed_hint)

            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName) ?: Intent()
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val failedId = replyFailedNotificationId(conversationId, replyText)
            val notification = NotificationCompat.Builder(context, MESSAGES_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(replyText)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(replyText)
                        .setSummaryText(hint)
                )
                .setCategory(NotificationCompat.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        failedId,
                        launchIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .build()

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(failedId, notification)
        } catch (e: Exception) {
            Logger.e(tag = TAG, throwable = e) { "Failed to surface the reply failure" }
        }
    }

    private companion object {
        const val TAG = "NotificationReply"
        const val MESSAGES_CHANNEL_ID = "messages"

        /** Keyed on the text too, so a second failed reply can't overwrite (and lose) the first. */
        fun replyFailedNotificationId(conversationId: String, replyText: String): Int =
            "reply-failed:$conversationId:$replyText".hashCode()
    }
}
