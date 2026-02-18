package id.homebase.chat.newconversation

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class NewConversationViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(NewConversationUiState(text = "New conversation"))
    val uiState: StateFlow<NewConversationUiState> = _uiState.asStateFlow()

    fun onUiAction(action: NewConversationUiAction) {
        when (action) {
            is NewConversationUiAction.BackClicked -> _uiState.update { it.copy(uiEvent = NewConversationUiEvent.Back)}

            // TODO - create conversation
//            viewModelScope.launch {
//                    val conversationId = conversationWriterService.createConversation(
//                        recipients = listOf(action.contact.odinId),
//                        title = "",
//                        payloadBundle = null,
//                    )
//
//                    _uiState.value =
//                        _uiState.value.copy(showingNewChatPane = false, searchQuery = "")
//
//                    loadMessagesForConversation(conversationId)
//                }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }
}