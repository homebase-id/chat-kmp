package id.homebase.chat.services

import id.homebase.core.notifications.NotificationMessagePreview
import id.homebase.core.notifications.NotificationMessageResolver
import kotlin.uuid.Uuid

/**
 * Bridges [NotificationMessageResolver] (homebase-common) to the chat message pipeline (#859):
 * resolves the referenced message via [MessageLookup] (local, or a single-header server fetch
 * when it hasn't synced yet) and returns its already-formatted preview — typed kinds (Poll,
 * Event, …) included, since [id.homebase.chat.data.MessageUiModel.content] is the same text the
 * conversation list shows — plus the sender display name.
 */
class ChatNotificationMessageResolver(
    private val messageLookup: MessageLookup,
) : NotificationMessageResolver {
    override suspend fun resolvePreview(
        conversationId: Uuid,
        messageId: Uuid,
    ): NotificationMessagePreview? {
        val message = messageLookup.resolveForNotification(conversationId, messageId) ?: return null
        val preview = message.content.takeIf { it.isNotBlank() } ?: return null
        return NotificationMessagePreview(
            senderName = message.displayName.takeIf { it.isNotBlank() },
            preview = preview,
        )
    }
}
