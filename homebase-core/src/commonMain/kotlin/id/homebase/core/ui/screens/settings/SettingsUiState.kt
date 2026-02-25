package id.homebase.core.ui.screens.settings

data class SettingsUiState(
    val isLoading: Boolean = false,
    val appName: String = "Homebase Chat",
    val appVersion: String,
    val loggedInDomain: String,

    val uiEvent: SettingsUiEvent? = null,
    val uiDialog: SettingsUiDialog? = null,
)

/** All possible user actions on Settings screen. */
sealed interface SettingsUiAction {
    data object LogoutClicked : SettingsUiAction
    data object DeleteAccount: SettingsUiAction
    data object OpenOwnerConsoleClicked : SettingsUiAction
}

/** One-off events for side effects (navigation). */
sealed interface SettingsUiEvent {
    data object LoggedOut : SettingsUiEvent
    data class OpenUrl(val url: String): SettingsUiEvent
}

sealed interface SettingsUiDialog {
    data object DeleteAccount: SettingsUiDialog
}
