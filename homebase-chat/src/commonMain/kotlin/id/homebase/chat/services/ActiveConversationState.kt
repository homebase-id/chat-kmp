package id.homebase.chat.services

import id.homebase.chat.data.MessageUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.uuid.Uuid

class ActiveConversationState {

    private val _messages =
        MutableStateFlow<Map<Uuid, List<MessageUiModel>>>(emptyMap())

    val messages: StateFlow<Map<Uuid, List<MessageUiModel>>> =
        _messages.asStateFlow()

    fun hasCachedMessages(conversationId: Uuid): Boolean =
        _messages.value.containsKey(conversationId)

    fun set(conversationId: Uuid, messages: List<MessageUiModel>) {
        _messages.update { current ->
            current.toMutableMap().apply {
                this[conversationId] = messages.sortedByDescending { it.userDate }
            }
        }
    }

    fun removeMessage(messageId: Uuid) {
        _messages.update { current ->
            current.mapValues { (_, messages) ->
                messages.filter { it.id != messageId }
            }
        }
    }

    fun upsert(conversationId: Uuid, incoming: List<MessageUiModel>) {
        if (incoming.isEmpty()) return
        if (!hasCachedMessages(conversationId)) return

        _messages.update { current ->
            val existing = current[conversationId] ?: return@update current
            current.toMutableMap().apply {
                this[conversationId] = upsertMessages(existing, incoming)
            }
        }
    }

    fun removeByFileId(conversationId: Uuid, fileId: Uuid) {
        _messages.update { current ->
            val existing = current[conversationId] ?: return@update current
            current.toMutableMap().apply {
                this[conversationId] = existing.filter { it.fileId != fileId }
            }
        }
    }

    private fun upsertMessages(
        current: List<MessageUiModel>,
        incoming: List<MessageUiModel>
    ): List<MessageUiModel> {
        val byId = current.associateBy { it.id }.toMutableMap()
        for (msg in incoming) {
            byId[msg.id] = msg
        }
        return byId.values.sortedByDescending { it.userDate }
    }
}