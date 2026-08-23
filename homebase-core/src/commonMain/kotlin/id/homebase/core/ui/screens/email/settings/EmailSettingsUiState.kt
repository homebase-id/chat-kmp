package id.homebase.core.ui.screens.email.settings

data class EmailSettingsUiState(
    val iconVisible: Boolean = true,
    val biometricsEnabled: Boolean = true,
)

sealed interface EmailSettingsUiAction {
    data object OpenEmailClicked : EmailSettingsUiAction
    data class SetIconVisible(val visible: Boolean) : EmailSettingsUiAction
    data class SetBiometricsEnabled(val enabled: Boolean) : EmailSettingsUiAction
}
