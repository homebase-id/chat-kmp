package id.homebase.core.ui.screens.appearance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.core.settings.Language
import id.homebase.core.settings.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class AppearanceSettingsViewModel(
    private val userPreferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppearanceSettingsUiState())
    val uiState: StateFlow<AppearanceSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            loadSettings()
        }
    }

    fun onAction(action: AppearanceSettingsUiAction) {
        when (action) {
            is AppearanceSettingsUiAction.LanguageSelected -> {
                userPreferences.language = action.language.code
                sendEvent(AppearanceSettingsUiEvent.SetLanguage(action.language.code))
                _uiState.value = _uiState.value.copy(selectedLanguage = action.language)
            }

            is AppearanceSettingsUiAction.ThemeSelected -> {
                userPreferences.theme = action.theme
                _uiState.value = _uiState.value.copy(selectedTheme = action.theme)
            }
        }
    }

    fun eventConsumed() {
        _uiState.update { it.copy(uiEvent = null) }
    }

    private fun loadSettings() {
        val savedLanguageCode = userPreferences.language
        val selectedLanguage = Language.fromCode(savedLanguageCode)

        _uiState.value = _uiState.value.copy(
            selectedLanguage = selectedLanguage,
            selectedTheme = userPreferences.theme
        )
    }

    private fun sendEvent(event: AppearanceSettingsUiEvent) {
        _uiState.update { it.copy(uiEvent = event) }
    }
}
