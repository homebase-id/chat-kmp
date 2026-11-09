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

/**
 * Bridge for iOS notification actions (reply, mark-as-read).
 * Provides callback-based wrappers around suspend functions
 * so they can be called from Swift.
 */
@OptIn(ExperimentalUuidApi::class)
class NotificationActionBridge(
    private val sendMessage: suspend (conversationId: Uuid, text: String) -> Unit,
    private val markAllAsRead: suspend (conversationId: Uuid) -> Unit,
    private val flushLastRead: suspend () -> Unit,
    private val clearNotifications: (conversationId: String) -> Unit,
    private val scope: CoroutineScope,
) {
    constructor(
        senderService: ChatMessageSenderService,
        actionService: ChatMessageActionService,
        conversationService: ConversationService,
        notificationService: NotificationService,
        scope: CoroutineScope,
    ) : this(
        sendMessage = { conversationId, text ->
            senderService.sendNewMessage(
                messageUniqueId = Uuid.random(),
                conversationId = conversationId,
                messageText = text,
                previousMessageUniqueId = null,
                payloadBundle = null,
            )
        },
        markAllAsRead = actionService::markAllAsRead,
        flushLastRead = conversationService::flushLastReadNow,
        clearNotifications = { notificationService.clearConversationNotifications(it, ClearReason.MarkRead) },
        scope = scope,
    )

    /**
     * Sends a reply message to a conversation.
     * @param conversationId The conversation UUID string.
     * @param text The reply text.
     * @param onComplete Called with true on success, false on failure.
     */
    fun sendReply(conversationId: String, text: String, onComplete: (Boolean) -> Unit) {
        scope.launch {
            try {
                sendMessage(Uuid.parse(conversationId), text)
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
                markAllAsRead(Uuid.parse(conversationId))
                // The writeback is debounced in memory; the process may not outlive it.
                flushLastRead()
                clearNotifications(conversationId)
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
