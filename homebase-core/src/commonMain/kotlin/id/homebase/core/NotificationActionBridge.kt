package id.homebase.core

import co.touchlab.kermit.Logger
import id.homebase.chat.services.ChatMessageActionService
import id.homebase.chat.services.ChatMessageSenderService
import id.homebase.chat.services.convo.ConversationService
import id.homebase.core.notifications.ClearReason
import id.homebase.core.notifications.NotificationService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatformTools
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** What a notification's Reply and Mark as read actions do; a seam so [NotificationActionBridge] is testable. */
interface NotificationActions {
    suspend fun sendMessage(conversationId: Uuid, text: String)
    suspend fun markAllAsRead(conversationId: Uuid)
    suspend fun flushLastRead()
    fun clearNotifications(conversationId: String)
}

@OptIn(ExperimentalUuidApi::class)
class ChatNotificationActions(
    private val senderService: ChatMessageSenderService,
    private val actionService: ChatMessageActionService,
    private val conversationService: ConversationService,
    private val notificationService: NotificationService,
) : NotificationActions {
    override suspend fun sendMessage(conversationId: Uuid, text: String) {
        senderService.sendNewMessage(
            messageUniqueId = Uuid.random(),
            conversationId = conversationId,
            messageText = text,
            previousMessageUniqueId = null,
            payloadBundle = null,
        )
    }

    override suspend fun markAllAsRead(conversationId: Uuid) = actionService.markAllAsRead(conversationId)

    override suspend fun flushLastRead() = conversationService.flushLastReadNow()

    override fun clearNotifications(conversationId: String) =
        notificationService.clearConversationNotifications(conversationId, ClearReason.MarkRead)
}

/**
 * Bridge for notification actions (reply, mark-as-read) on Android and iOS.
 * Provides callback-based wrappers around suspend functions
 * so they can be called from Swift.
 */
@OptIn(ExperimentalUuidApi::class)
class NotificationActionBridge(
    private val actions: NotificationActions,
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
                actions.sendMessage(Uuid.parse(conversationId), text)
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
                actions.markAllAsRead(Uuid.parse(conversationId))
                // The writeback is debounced in memory; the process may not outlive it.
                actions.flushLastRead()
                actions.clearNotifications(conversationId)
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
