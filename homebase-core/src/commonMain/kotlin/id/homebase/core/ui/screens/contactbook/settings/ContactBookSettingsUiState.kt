package id.homebase.core.ui.screens.contactbook.settings

data class ContactBookSettingsUiState(
    val iconVisible: Boolean = true,
    val biometricsEnabled: Boolean = false,
)

sealed interface ContactBookSettingsUiAction {
    data object OpenContactsClicked : ContactBookSettingsUiAction
    data class SetIconVisible(val visible: Boolean) : ContactBookSettingsUiAction
    data class SetBiometricsEnabled(val enabled: Boolean) : ContactBookSettingsUiAction
}
