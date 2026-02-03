package id.homebase.core.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.api.youauth.YouAuthFlowManager
import id.homebase.core.settings.Language
import id.homebase.core.settings.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val userPreferences: UserPreferences,
    private val youAuthFlowManager: YouAuthFlowManager
): ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

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
            is SettingsUiAction.LanguageSelected -> {
                saveLanguage(action.language)
            }

            is SettingsUiAction.LogoutClicked -> {
                viewModelScope.launch {
                    youAuthFlowManager.logout()
                    sendEvent(SettingsUiEvent.LoggedOut)
                }
            }
        }
    }

    fun eventConsumed() {
        _uiState.update {
            it.copy(uiEvent = null)
        }
    }

    private fun saveLanguage(language: Language) {
        userPreferences.language = language.code
        sendEvent(SettingsUiEvent.SetLanguage(language.code))
        _uiState.value = _uiState.value.copy(selectedLanguage = language)
    }

    private fun sendEvent(event: SettingsUiEvent) {
        _uiState.update {  it.copy(uiEvent = event) }
    }
}
