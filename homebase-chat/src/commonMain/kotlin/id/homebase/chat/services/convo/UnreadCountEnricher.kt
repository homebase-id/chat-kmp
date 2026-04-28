package id.homebase.chat.services.convo

import kotlin.uuid.Uuid

/**
 * Narrow seam for enriching a single conversation's unread count.
 *
 * Lets callers like ChatMessageActionService depend on just this one method
 * rather than the full ConversationStream, which keeps their test fixtures
 * light. ConversationStream is the production implementation.
 */
interface UnreadCountEnricher {
    suspend fun enrichOneConversationWithUnreadCount(conversationId: Uuid)
}
