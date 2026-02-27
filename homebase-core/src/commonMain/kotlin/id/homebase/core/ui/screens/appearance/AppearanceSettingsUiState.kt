package id.homebase.core.ui.screens.appearance

import id.homebase.core.settings.Language
import id.homebase.core.settings.ThemeState

data class AppearanceSettingsUiState(
    val selectedLanguage: Language = Language.SYSTEM,
    val selectedTheme: ThemeState = ThemeState.System,
    val availableLanguages: List<Language> = Language.entries,
    val uiEvent: AppearanceSettingsUiEvent? = null,
)

sealed interface AppearanceSettingsUiAction {
    data class LanguageSelected(val language: Language) : AppearanceSettingsUiAction
    data class ThemeSelected(val theme: ThemeState) : AppearanceSettingsUiAction
}

sealed interface AppearanceSettingsUiEvent {
    data class SetLanguage(val language: String) : AppearanceSettingsUiEvent
}
