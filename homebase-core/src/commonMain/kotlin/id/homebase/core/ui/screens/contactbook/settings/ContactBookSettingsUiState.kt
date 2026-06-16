package id.homebase.core.ui.screens.contactbook.settings

data class ContactBookSettingsUiState(
    val iconVisible: Boolean = true,
)

sealed interface ContactBookSettingsUiAction {
    data object OpenContactsClicked : ContactBookSettingsUiAction
    data class SetIconVisible(val visible: Boolean) : ContactBookSettingsUiAction
}
