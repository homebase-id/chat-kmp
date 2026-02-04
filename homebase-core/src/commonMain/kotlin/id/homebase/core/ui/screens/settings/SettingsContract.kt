package id.homebase.core.ui.screens.settings

import id.homebase.core.settings.Language

data class SettingsUiState(
    val isLoading: Boolean = false,
    val appName: String = "Homebase Chat",
    val selectedLanguage: Language = Language.SYSTEM,
    val availableLanguages: List<Language> = Language.entries,
    val uiEvent: SettingsUiEvent? = null,
    val loggedInDomain: String
)

/** All possible user actions on Settings screen. */
sealed interface SettingsUiAction {
    data class LanguageSelected(val language: Language) : SettingsUiAction

    data object LogoutClicked : SettingsUiAction

}

/** One-off events for side effects (navigation). */
sealed interface SettingsUiEvent {
    data class SetLanguage(val language: String) : SettingsUiEvent

    data object LoggedOut : SettingsUiEvent

}
