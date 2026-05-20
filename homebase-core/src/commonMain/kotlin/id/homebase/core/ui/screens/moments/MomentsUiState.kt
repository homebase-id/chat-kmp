package id.homebase.core.ui.screens.moments

data class MomentsUiState(
    val isCheckingPermissions: Boolean = false,
    val setupInitiated: Boolean = false,
)

sealed interface MomentsUiAction {
    data object SetupClicked : MomentsUiAction
    data object DismissOnboardingClicked : MomentsUiAction
}

sealed interface MomentsUiEvent {
    data object Activated : MomentsUiEvent
    data object CloseOnboarding : MomentsUiEvent
}
