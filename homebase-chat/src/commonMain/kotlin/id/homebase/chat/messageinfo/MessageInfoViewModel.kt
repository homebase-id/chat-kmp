package id.homebase.chat.messageinfo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.navigation.toRoute
import id.homebase.core.ui.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MessageInfoViewModel(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val messageInfo = savedStateHandle.toRoute<Route.MessageInfo>()
    private val _uiState = MutableStateFlow(MessageInfoUiState(text = messageInfo.messageId))
    val uiState: StateFlow<MessageInfoUiState> = _uiState.asStateFlow()

    fun onUiAction(action: MessageInfo) {
        when (action) {
            is MessageInfo.BackClicked -> _uiState.update { it.copy(uiEvent = MessageInfoUiEvent.Back)}
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }
}