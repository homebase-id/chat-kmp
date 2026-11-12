package id.homebase.core.notifications

import id.homebase.chat.services.convo.ConversationsData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Clears a chat's notifications when its unread count drops to 0, however it got read. */
@OptIn(ExperimentalUuidApi::class)
class ReadNotificationClearer(
    private val conversations: Flow<ConversationsData>,
    private val clear: (conversationId: String) -> Unit,
) {
    suspend fun run() {
        var last = emptyMap<Uuid, Int>()
        // Before the unread pass every chat reads 0, which would look like everything was read.
        conversations.filter { it.enrichment.hasUnreadCounts }.collect { data ->
            val current = data.items.associate { it.id to it.unreadCount }
            for ((id, unread) in current) {
                if (unread == 0 && (last[id] ?: 0) > 0) clear(id.toString())
            }
            last = current
        }
    }
}
