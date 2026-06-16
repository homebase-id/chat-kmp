package id.homebase.core.ui.screens.lists

data class ListsUiState(
    val isCheckingPermissions: Boolean = false,
    val setupInitiated: Boolean = false,
)

sealed interface ListsUiAction {
    data object SetupClicked : ListsUiAction
    data object DismissOnboardingClicked : ListsUiAction
}

sealed interface ListsUiEvent {
    data object Activated : ListsUiEvent
    data object CloseOnboarding : ListsUiEvent
}
