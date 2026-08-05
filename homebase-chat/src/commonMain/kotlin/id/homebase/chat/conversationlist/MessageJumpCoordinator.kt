package id.homebase.chat.conversationlist

import id.homebase.core.util.ScrollPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

/** Outcome of matching an armed jump target against a freshly emitted window. */
internal sealed interface JumpTargetResolution {
    /** Rendered at [index]; the coordinator has disarmed. */
    data class Landed(val index: Int) : JumpTargetResolution

    /** Nothing armed, or not rendered yet — stays armed so a later emission retries. */
    data object Pending : JumpTargetResolution

    /** In the window but filtered out of the rendered list, so it can never land. */
    data object ExcludedFromView : JumpTargetResolution
}

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
    private val isExcludedFromView: (conversationId: Uuid, messageId: Uuid) -> Boolean,
    private val loadAroundMessage: suspend (conversationId: Uuid, messageId: Uuid) -> Boolean,
    private val reportUnavailable: (conversationId: Uuid, messageId: Uuid) -> Unit,
    private val reportExcluded: (conversationId: Uuid, messageId: Uuid) -> Unit,
) {
    var pendingTarget: Uuid? = null
        private set

    private var pendingConversationId: Uuid? = null

    fun arm(conversationId: Uuid, messageId: Uuid?) {
        pendingTarget = messageId
        pendingConversationId = messageId?.let { conversationId }
    }

    fun disarm() {
        pendingTarget = null
        pendingConversationId = null
    }

    /**
     * Matches the armed target against a freshly emitted window, disarming once
     * it lands or once it turns out this view will never render it.
     */
    fun resolvePendingJump(messages: List<MessageListContentModel>): JumpTargetResolution {
        val target = pendingTarget ?: return JumpTargetResolution.Pending
        val conversationId = pendingConversationId ?: return JumpTargetResolution.Pending
        val index = messages.indexOfLast {
            it is MessageListContentModel.Message && it.message.id == target
        }
        if (index >= 0) {
            disarm()
            return JumpTargetResolution.Landed(index)
        }
        if (!isExcludedFromView(conversationId, target)) return JumpTargetResolution.Pending
        disarm()
        reportExcluded(conversationId, target)
        return JumpTargetResolution.ExcludedFromView
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
        // Before the window checks: an excluded target IS in the window, so they
        // would read it as "not here yet" and arm a retry that can never resolve.
        if (isExcludedFromView(conversationId, messageId)) {
            reportExcluded(conversationId, messageId)
            return
        }

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

        arm(conversationId, messageId)
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
