package id.homebase.core.ui.screens.vault.settings

data class VaultSettingsUiState(
    val iconVisible: Boolean = true,
    val biometricsEnabled: Boolean = true,
    val deviceAuthAvailable: Boolean = true,
) {
    // Kept while still ON so an unprotected lock is surfaced, not silently dropped.
    val showBiometricsRow: Boolean get() = deviceAuthAvailable || biometricsEnabled
}

sealed interface VaultSettingsUiAction {
    data object OpenVaultClicked : VaultSettingsUiAction
    data class SetIconVisible(val visible: Boolean) : VaultSettingsUiAction
    data class SetBiometricsEnabled(val enabled: Boolean) : VaultSettingsUiAction
}
