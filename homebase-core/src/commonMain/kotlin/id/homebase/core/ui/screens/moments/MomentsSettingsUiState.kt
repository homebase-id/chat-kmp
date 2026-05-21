package id.homebase.core.ui.screens.moments

data class MomentsSettingsUiState(
    val iconVisible: Boolean = true,
)

sealed interface MomentsSettingsUiAction {
    data object OpenMomentsClicked : MomentsSettingsUiAction
    data class SetIconVisible(val visible: Boolean) : MomentsSettingsUiAction
}
