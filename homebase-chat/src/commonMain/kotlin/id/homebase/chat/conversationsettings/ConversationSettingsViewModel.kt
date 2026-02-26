package id.homebase.chat.conversationsettings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.navigation.toRoute
import id.homebase.core.ui.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ConversationSettingsViewModel(
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val route = savedStateHandle.toRoute<Route.ConversationSettings>()
    private val _uiState = MutableStateFlow(ConversationSettingsUiState(text = route.conversationId))
    val uiState: StateFlow<ConversationSettingsUiState> = _uiState.asStateFlow()

    fun onUiAction(action: ConversationSettingsUiAction) {
        when (action) {
            is ConversationSettingsUiAction.BackClicked -> _uiState.update { it.copy(uiEvent = ConversationSettingsUiEvent.Back)}
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }
}