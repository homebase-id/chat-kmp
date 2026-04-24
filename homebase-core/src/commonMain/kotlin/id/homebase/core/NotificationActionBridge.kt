package id.homebase.core

import co.touchlab.kermit.Logger
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.core.notifications.NotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.mp.KoinPlatformTools
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Bridge for iOS notification actions (reply, mark-as-read).
 * Provides callback-based wrappers around suspend functions
 * so they can be called from Swift.
 */
@OptIn(ExperimentalUuidApi::class)
class NotificationActionBridge(
    private val senderService: ChatMessageSenderService,
    private val actionService: ChatMessageActionService,
    private val notificationService: NotificationService,
    private val scope: CoroutineScope,
) {

    /**
     * Sends a reply message to a conversation.
     * @param conversationId The conversation UUID string.
     * @param text The reply text.
     * @param onComplete Called with true on success, false on failure.
     */
    fun sendReply(conversationId: String, text: String, onComplete: (Boolean) -> Unit) {
        scope.launch {
            try {
                val convoUuid = Uuid.parse(conversationId)
                val messageUuid = Uuid.random()
                senderService.sendNewMessage(
                    messageUniqueId = messageUuid,
                    conversationId = convoUuid,
                    messageText = text,
                    previousMessageUniqueId = null,
                    payloadBundle = null,
                )
                Logger.i(tag = "NotificationActionBridge") {
                    "Reply sent to $conversationId"
                }
                onComplete(true)
            } catch (e: Exception) {
                Logger.e(tag = "NotificationActionBridge") {
                    "Failed to send reply: ${e.message}"
                }
                onComplete(false)
            }
        }
    }

    /**
     * Marks a conversation as read.
     * @param conversationId The conversation UUID string.
     * @param onComplete Called with true on success, false on failure.
     */
    fun markAsRead(conversationId: String, onComplete: (Boolean) -> Unit) {
        scope.launch {
            try {
                val convoUuid = Uuid.parse(conversationId)
                notificationService.clearNotificationCount(conversationId)
                //TODO: we need the message Id here
//                actionService.markAsReadLatestFileCreated(convoUuid, emptyList())
                Logger.i(tag = "NotificationActionBridge") {
                    "Marked $conversationId as read"
                }
                onComplete(true)
            } catch (e: Exception) {
                Logger.e(tag = "NotificationActionBridge") {
                    "Failed to mark as read: ${e.message}"
                }
                onComplete(false)
            }
        }
    }

    companion object {
        fun fromKoin(): NotificationActionBridge =
            KoinPlatformTools.defaultContext().get().get()
    }
}
