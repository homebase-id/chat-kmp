package id.homebase.chat.services

import id.homebase.api.client.drives.query.QueryBatchCursor
import id.homebase.api.client.drives.query.TimeRowCursor
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.chat.data.MessageUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

data class MessageWindow(
    val messages: List<MessageUiModel> = emptyList(),
    val olderCursor: QueryBatchCursor? = null,
    val newerCursor: QueryBatchCursor? = null,
    val hasOlderMessages: Boolean = false,
    val hasNewerMessages: Boolean = false,
    val isLoadingOlder: Boolean = false,
    val isLoadingNewer: Boolean = false,
)

class PaginatedConversationState {

    companion object {
        const val PAGE_SIZE = 75
        const val MAX_WINDOW_SIZE = 300
        const val LOAD_MORE_THRESHOLD = 15
    }

    private val _windows = MutableStateFlow<Map<Uuid, MessageWindow>>(emptyMap())
    val windows: StateFlow<Map<Uuid, MessageWindow>> = _windows.asStateFlow()

    fun setInitialWindow(
        conversationId: Uuid,
        messages: List<MessageUiModel>,
        olderCursor: QueryBatchCursor?,
        hasOlderMessages: Boolean,
        newerCursor: QueryBatchCursor?,
        hasNewerMessages: Boolean,
    ) {
        _windows.value = _windows.value.toMutableMap().apply {
            this[conversationId] = MessageWindow(
                messages = messages.sortedBy { it.created },
                olderCursor = olderCursor,
                newerCursor = newerCursor,
                hasOlderMessages = hasOlderMessages,
                hasNewerMessages = hasNewerMessages,
            )
        }
    }

    fun prependOlderMessages(
        conversationId: Uuid,
        olderMessages: List<MessageUiModel>,
        olderCursor: QueryBatchCursor?,
        hasMore: Boolean,
    ) {
        _windows.value = _windows.value.toMutableMap().apply {
            val current = this[conversationId] ?: return
            val combined = (olderMessages + current.messages)
                .distinctBy { it.id }
                .sortedBy { it.created }

            val (trimmed, newerCursor, hasNewer) = trimTail(
                combined, current.newerCursor, current.hasNewerMessages
            )

            this[conversationId] = current.copy(
                messages = trimmed,
                olderCursor = olderCursor,
                hasOlderMessages = hasMore,
                newerCursor = newerCursor,
                hasNewerMessages = hasNewer,
                isLoadingOlder = false,
            )
        }
    }

    fun appendNewerMessages(
        conversationId: Uuid,
        newerMessages: List<MessageUiModel>,
        newerCursor: QueryBatchCursor?,
        hasMore: Boolean,
    ) {
        _windows.value = _windows.value.toMutableMap().apply {
            val current = this[conversationId] ?: return
            val combined = (current.messages + newerMessages)
                .distinctBy { it.id }
                .sortedBy { it.created }

            val (trimmed, olderCursor, hasOlder) = trimHead(
                combined, current.olderCursor, current.hasOlderMessages
            )

            this[conversationId] = current.copy(
                messages = trimmed,
                olderCursor = olderCursor,
                hasOlderMessages = hasOlder,
                newerCursor = newerCursor,
                hasNewerMessages = hasMore,
                isLoadingNewer = false,
            )
        }
    }

    fun upsertMessage(conversationId: Uuid, incoming: List<MessageUiModel>) {
        if (incoming.isEmpty()) return
        _windows.value = _windows.value.toMutableMap().apply {
            val current = this[conversationId] ?: return
            val byId = current.messages.associateBy { it.id }.toMutableMap()
            for (msg in incoming) {
                byId[msg.id] = msg
            }
            this[conversationId] = current.copy(
                messages = byId.values.sortedBy { it.created }
            )
        }
    }

    fun removeMessage(messageId: Uuid) {
        _windows.value = _windows.value.mapValues { (_, window) ->
            window.copy(messages = window.messages.filter { it.id != messageId })
        }
    }

    fun setLoadingOlder(conversationId: Uuid, loading: Boolean) {
        _windows.value = _windows.value.toMutableMap().apply {
            val current = this[conversationId] ?: return
            this[conversationId] = current.copy(isLoadingOlder = loading)
        }
    }

    fun setLoadingNewer(conversationId: Uuid, loading: Boolean) {
        _windows.value = _windows.value.toMutableMap().apply {
            val current = this[conversationId] ?: return
            this[conversationId] = current.copy(isLoadingNewer = loading)
        }
    }

    fun getWindow(conversationId: Uuid): MessageWindow? {
        return _windows.value[conversationId]
    }

    /**
     * Trim messages from the tail (newest end) when the list exceeds MAX_WINDOW_SIZE.
     * Returns the trimmed list, updated newerCursor, and updated hasNewerMessages.
     */
    private fun trimTail(
        messages: List<MessageUiModel>,
        currentNewerCursor: QueryBatchCursor?,
        currentHasNewer: Boolean,
    ): Triple<List<MessageUiModel>, QueryBatchCursor?, Boolean> {
        if (messages.size <= MAX_WINDOW_SIZE) {
            return Triple(messages, currentNewerCursor, currentHasNewer)
        }
        val trimmed = messages.take(MAX_WINDOW_SIZE)
        val boundaryMessage = trimmed.last()
        val newerCursor = QueryBatchCursor(
            paging = TimeRowCursor(
                UnixTimeUtc(boundaryMessage.created),
                0L
            )
        )
        return Triple(trimmed, newerCursor, true)
    }

    /**
     * Trim messages from the head (oldest end) when the list exceeds MAX_WINDOW_SIZE.
     * Returns the trimmed list, updated olderCursor, and updated hasOlderMessages.
     */
    private fun trimHead(
        messages: List<MessageUiModel>,
        currentOlderCursor: QueryBatchCursor?,
        currentHasOlder: Boolean,
    ): Triple<List<MessageUiModel>, QueryBatchCursor?, Boolean> {
        if (messages.size <= MAX_WINDOW_SIZE) {
            return Triple(messages, currentOlderCursor, currentHasOlder)
        }
        val trimmed = messages.takeLast(MAX_WINDOW_SIZE)
        val boundaryMessage = trimmed.first()
        val olderCursor = QueryBatchCursor(
            paging = TimeRowCursor(
                UnixTimeUtc(boundaryMessage.created),
                Long.MAX_VALUE
            )
        )
        return Triple(trimmed, olderCursor, true)
    }
}
