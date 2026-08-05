package id.homebase.chat.conversationlist

import id.homebase.core.util.ScrollPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

/**
 * Single decision point for "take me to this message" — pinned bar, reply quote,
 * search result and the conversation-open path all route through here.
 *
 * A re-centered window arrives reactively, so the load-around branch can't
 * resolve a scroll index in the same tick (the rendered list is still the old
 * window). It arms [pendingTarget] and the message collector in
 * `ConversationListViewModel.loadMessagesForConversation` resolves it when the
 * new window emits.
 */
internal class MessageJumpCoordinator(
    private val messagesUiState: MutableStateFlow<MessageListUiState>,
    private val isMessageInWindow: (conversationId: Uuid, messageId: Uuid) -> Boolean,
    private val loadAroundMessage: suspend (conversationId: Uuid, messageId: Uuid) -> Boolean,
    private val reportUnavailable: (conversationId: Uuid, messageId: Uuid) -> Unit,
) {
    var pendingTarget: Uuid? = null
        private set

    fun arm(messageId: Uuid?) {
        pendingTarget = messageId
    }

    fun disarm() {
        pendingTarget = null
    }

    /**
     * Index of the armed target in a freshly emitted window, disarming once it
     * lands. Null keeps the coordinator armed so a later emission retries —
     * the target may still be syncing in.
     */
    fun resolvePendingIndex(messages: List<MessageListContentModel>): Int? {
        val target = pendingTarget ?: return null
        val index = messages.indexOfLast {
            it is MessageListContentModel.Message && it.message.id == target
        }
        if (index < 0) return null
        disarm()
        return index
    }

    /**
     * Re-seeds a window centered on [messageId] unless one already holds it.
     * False means the target is not on disk; reporting that is the caller's, because
     * on a notification tap it means "not synced yet" and is waited on rather than told.
     */
    suspend fun ensureWindowContains(conversationId: Uuid, messageId: Uuid): Boolean {
        if (isMessageInWindow(conversationId, messageId)) return true
        return loadAroundMessage(conversationId, messageId)
    }

    suspend fun jumpToMessage(conversationId: Uuid, messageId: Uuid) {
        val renderedIndex = if (isMessageInWindow(conversationId, messageId)) {
            messagesUiState.value.messages.indexOfLast {
                it is MessageListContentModel.Message && it.message.id == messageId
            }
        } else {
            -1
        }

        if (renderedIndex != -1) {
            messagesUiState.update {
                it.copy(
                    scrollPosition = ScrollPosition(
                        firstVisibleItemIndex = renderedIndex,
                        triggerScroll = true,
                    ),
                    highlightedMessageId = messageId,
                )
            }
            return
        }

        arm(messageId)
        messagesUiState.update { it.copy(highlightedMessageId = messageId) }
        if (!ensureWindowContains(conversationId, messageId)) {
            reportUnavailable(conversationId, messageId)
            if (pendingTarget == messageId) disarm()
            messagesUiState.update {
                if (it.highlightedMessageId == messageId) it.copy(highlightedMessageId = null)
                else it
            }
        }
    }
}
