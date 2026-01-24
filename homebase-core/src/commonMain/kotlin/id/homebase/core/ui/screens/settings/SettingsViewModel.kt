package id.homebase.core.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.settings.Language
import id.homebase.core.settings.UserPreferences
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val userPreferences: UserPreferences,
    private val youAuthFlowManager: YouAuthFlowManager
): ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private val _uiEvent = Channel<SettingsUiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        val savedLanguageCode = userPreferences.language
        val selectedLanguage = Language.fromCode(savedLanguageCode)
        _uiState.value = _uiState.value.copy(selectedLanguage = selectedLanguage)
    }

    /** Single entry point for all UI actions. */
    fun onAction(action: SettingsUiAction) {
        when (action) {
            is SettingsUiAction.ChatListClicked -> {
                sendEvent(SettingsUiEvent.NavigateToChatList)
            }
            is SettingsUiAction.LanguageSelected -> {
                saveLanguage(action.language)
            }

            is SettingsUiAction.LogoutClicked -> {
                viewModelScope.launch {
                    youAuthFlowManager.logout()
                    _uiEvent.send(SettingsUiEvent.LoggedOut)
                }
            }
        }
    }

    private fun saveLanguage(language: Language) {
        userPreferences.language = language.code
        sendEvent(SettingsUiEvent.SetLanguage(language.code))
        _uiState.value = _uiState.value.copy(selectedLanguage = language)
    }

    private fun sendEvent(event: SettingsUiEvent) {
        viewModelScope.launch { _uiEvent.send(event) }
    }
}
