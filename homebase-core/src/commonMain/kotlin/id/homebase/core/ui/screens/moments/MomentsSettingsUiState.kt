package id.homebase.core.ui.screens.moments

import id.homebase.api.image.SendQuality
import id.homebase.core.settings.SendQualityApp

data class MomentsSettingsUiState(
    val iconVisible: Boolean = true,
    val quality: SendQuality = SendQualityApp.Moments.default,
    val autoSaveIncomingMedia: Boolean = false,
)

sealed interface MomentsSettingsUiAction {
    data object OpenMomentsClicked : MomentsSettingsUiAction
    data class SetIconVisible(val visible: Boolean) : MomentsSettingsUiAction
    data class SetQuality(val quality: SendQuality) : MomentsSettingsUiAction
    data class SetAutoSaveIncomingMedia(val enabled: Boolean) : MomentsSettingsUiAction
}
