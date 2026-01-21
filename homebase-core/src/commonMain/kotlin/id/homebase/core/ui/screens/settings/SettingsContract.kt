package id.homebase.core.ui.screens.settings

data class SettingsUiState(
        val isLoading: Boolean = false,
        val appName: String = "Homebase Chat"
)

/** All possible user actions on Home screen. */
sealed interface SettingsUiAction {
    data object ChatListClicked : SettingsUiAction
}

/** One-off events for side effects (navigation). */
sealed interface SettingsUiEvent {
    data object NavigateToChatList : SettingsUiEvent
}
