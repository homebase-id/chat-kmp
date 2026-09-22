package id.homebase.core.ui.screens.keyboard

data class KeyboardSettingsUiState(
    val enterSendsMessage: Boolean = false,
    val arrowUpEditsLastMessage: Boolean = true,
)

sealed interface KeyboardSettingsUiAction {
    data class SetEnterSendsMessage(val enabled: Boolean) : KeyboardSettingsUiAction
    data class SetArrowUpEditsLastMessage(val enabled: Boolean) : KeyboardSettingsUiAction
}
