package id.homebase.chat.messageinfo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.chat.services.ChatMessageStream
import id.homebase.core.ui.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

class MessageInfoViewModel(
    savedStateHandle: SavedStateHandle,
    private val chatMessageStream: ChatMessageStream,
    private val ownerSessionRepository: OwnerSessionRepository,
) : ViewModel() {

    val messageInfo = savedStateHandle.toRoute<Route.MessageInfo>()
    private val _uiState = MutableStateFlow(MessageInfoUiState())
    val uiState: StateFlow<MessageInfoUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            ownerSessionRepository.user.collect { session ->
                _uiState.update { it.copy(ownerSession = session) }
            }
        }
        viewModelScope.launch {
            try {
                val message = chatMessageStream.getMessage(Uuid.parse(messageInfo.messageId))
                _uiState.update { it.copy(message = message, isLoading = false) }
            } catch (_: Exception) {
                _uiState
            }
        }
    }

    fun onUiAction(action: MessageInfoUiAction) {
        when (action) {
            is MessageInfoUiAction.BackClicked -> _uiState.update { it.copy(uiEvent = MessageInfoUiEvent.Back)}
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }
}