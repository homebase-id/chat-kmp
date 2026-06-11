package id.homebase.core.ui.screens.location.settings

data class LocationSettingsUiState(
    val iconVisible: Boolean = true,
)

sealed interface LocationSettingsUiAction {
    data object OpenLocationClicked : LocationSettingsUiAction
    data class SetIconVisible(val visible: Boolean) : LocationSettingsUiAction
}
