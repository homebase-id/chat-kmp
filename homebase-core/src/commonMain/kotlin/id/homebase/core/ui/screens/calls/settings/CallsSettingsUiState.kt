package id.homebase.core.ui.screens.calls.settings

data class CallsSettingsUiState(
    val enabled: Boolean = false,
)

sealed interface CallsSettingsUiAction {
    data class SetEnabled(val enabled: Boolean) : CallsSettingsUiAction
}
