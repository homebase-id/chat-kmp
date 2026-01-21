package id.homebase.core.ui.screens.home

data class HomeUiState(
        val isLoading: Boolean = false,
        val appName: String = "Homebase Chat"
)

/** All possible user actions on Home screen. */
sealed interface HomeUiAction {
    data object ChatListClicked : HomeUiAction
}

/** One-off events for side effects (navigation). */
sealed interface HomeUiEvent {
    data object NavigateToChatList : HomeUiEvent
}
