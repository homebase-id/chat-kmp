package id.homebase.core.ui.screens.vault

data class VaultUiState(
    val showPermissionDialog: Boolean = false,
)

sealed interface VaultUiAction {
    data object SetupClicked : VaultUiAction
    data object DismissOnboardingClicked : VaultUiAction
    data object PermissionExtendClicked : VaultUiAction
    data object PermissionCancelClicked : VaultUiAction
}

sealed interface VaultUiEvent {
    /** Activation state and biometric-toggle state are known — navigate to the Vault screen. */
    data object NavigateToVault : VaultUiEvent
    /** Not activated — show the onboarding screen. */
    data object NavigateToOnboarding : VaultUiEvent
    /** Authentication failed / cancelled — go back to the prior screen. */
    data object Back : VaultUiEvent
}
