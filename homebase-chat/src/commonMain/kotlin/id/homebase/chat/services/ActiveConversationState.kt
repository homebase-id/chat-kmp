package id.homebase.chat.services

import id.homebase.chat.data.MessageUiModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.uuid.Uuid

class ActiveConversationState {

    private val _messages =
        MutableStateFlow<Map<Uuid, List<MessageUiModel>>>(emptyMap())

    val messages: StateFlow<Map<Uuid, List<MessageUiModel>>> =
        _messages.asStateFlow()

    fun set(conversationId: Uuid, messages: List<MessageUiModel>) {
        _messages.value =
            _messages.value.toMutableMap().apply {
                this[conversationId] = messages
            }
    }

    fun upsert(conversationId: Uuid, incoming: List<MessageUiModel>) {
        if (incoming.isEmpty()) return

        val updated =
            _messages.value.toMutableMap().apply {
                val current = this[conversationId].orEmpty()
                this[conversationId] = upsertMessages(current, incoming)
            }

        _messages.value = updated
    }

    private fun upsertMessages(
        current: List<MessageUiModel>,
        incoming: List<MessageUiModel>
    ): List<MessageUiModel> {
        val byId = current.associateBy { it.id }.toMutableMap()
        for (msg in incoming) {
            byId[msg.id] = msg
        }
        return byId.values.sortedByDescending { it.created }
    }
}