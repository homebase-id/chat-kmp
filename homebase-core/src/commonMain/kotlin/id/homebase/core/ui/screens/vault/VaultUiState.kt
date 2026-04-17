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
    /** User completed the permission extension — navigate onboarding → Vault. */
    data object Activated : VaultUiEvent
    /** User dismissed onboarding — leave the onboarding screen. */
    data object CloseOnboarding : VaultUiEvent
}
