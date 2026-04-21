package id.homebase.core.ui.screens.vault

data class VaultUiState(
    val isCheckingPermissions: Boolean = false,
)

sealed interface VaultUiAction {
    data object SetupClicked : VaultUiAction
    data object DismissOnboardingClicked : VaultUiAction
}

sealed interface VaultUiEvent {
    data object Activated : VaultUiEvent
    data object CloseOnboarding : VaultUiEvent
}
