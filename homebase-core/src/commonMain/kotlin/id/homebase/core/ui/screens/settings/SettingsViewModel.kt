package id.homebase.core.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.settings.UserPreferences
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val userPreferences: UserPreferences
): ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _uiEvent = Channel<SettingsUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()


    /** Single entry point for all UI actions. */
    fun onAction(action: SettingsUiAction) {
        when (action) {
            is SettingsUiAction.ChatListClicked -> {
                sendEvent(SettingsUiEvent.NavigateToChatList)
            }
        }
    }

    private fun sendEvent(event: SettingsUiEvent) {
        viewModelScope.launch { _uiEvent.send(event) }
    }
}
