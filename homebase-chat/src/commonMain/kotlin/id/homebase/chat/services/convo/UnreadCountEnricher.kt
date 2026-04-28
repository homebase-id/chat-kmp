package id.homebase.chat.services.convo

import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Narrow seam for patching a single conversation's mark-as-read state into
 * the in-memory ConversationStream.
 *
 * Used by ChatMessageActionService after it advances ChatReadCount.lastReadTime,
 * to immediately reflect the new lastRead and recomputed unreadCount in the UI
 * without waiting for the conversation-file appdata round-trip via BatchReceived.
 */
interface UnreadCountEnricher {
    /**
     * Set [conversationId]'s [ConversationUiModel.lastRead] to [newLastRead] and
     * recompute its [ConversationUiModel.unreadCount] from ChatReadCount in a
     * single atomic state emission. No-op if the conversation isn't in memory.
     */
    suspend fun applyLocalAdvance(conversationId: Uuid, newLastRead: Instant)
}
