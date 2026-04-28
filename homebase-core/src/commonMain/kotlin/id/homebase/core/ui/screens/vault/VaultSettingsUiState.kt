package id.homebase.core.ui.screens.vault

data class VaultSettingsUiState(
    val iconVisible: Boolean = true,
    val biometricsEnabled: Boolean = true,
)

sealed interface VaultSettingsUiAction {
    data object OpenVaultClicked : VaultSettingsUiAction
    data class SetIconVisible(val visible: Boolean) : VaultSettingsUiAction
    data class SetBiometricsEnabled(val enabled: Boolean) : VaultSettingsUiAction
}
