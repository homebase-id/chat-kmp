package id.homebase.chat

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

sealed interface ChatListUiEvent {
    data class NavigateToMessages(val conversationId: String) : ChatListUiEvent
    data object NavigateBack : ChatListUiEvent
}

sealed interface ChatListUiAction {
    data class ConversationClicked(val conversationId: String) : ChatListUiAction
    data object BackClicked : ChatListUiAction
}

@Immutable
data class ChatListUiState(
    val conversations: ImmutableList<String> = persistentListOf(),
)

class ChatListViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ChatListUiState(
        conversations = persistentListOf(
            "Cupcake",
            "Donut",
            "Eclair",
            "Froyo",
            "Gingerbread",
            "Honeycomb",
            "Ice cream sandwich",
            "Jelly bean",
            "Kitkat",
            "Lollipop",
            "Marshmallow",
            "Nougat",
            "Oreo",
            "Pie",
        )
    ))
    val uiState: StateFlow<ChatListUiState> = _uiState.asStateFlow()

    private val _uiEvent = Channel<ChatListUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    fun onAction(action: ChatListUiAction) {
        when (action) {
            is ChatListUiAction.ConversationClicked -> {
                sendEvent(ChatListUiEvent.NavigateToMessages(action.conversationId))
            }
            ChatListUiAction.BackClicked -> {
                sendEvent(ChatListUiEvent.NavigateBack)
            }
        }
    }

    private fun sendEvent(event: ChatListUiEvent) {
        viewModelScope.launch { _uiEvent.send(event) }
    }
}
