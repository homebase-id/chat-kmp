package id.homebase.core.ui.screens.media

import id.homebase.api.image.MediaQuality

data class MediaSettingsUiState(
    val mediaQuality: MediaQuality = MediaQuality.STANDARD,
    val autoSaveIncomingMedia: Boolean = false,
    val autoSaveOnUnmeteredOnly: Boolean = true,
    val mirrorFrontCamera: Boolean = true,
)

sealed interface MediaSettingsUiAction {
    data class SetMediaQuality(val quality: MediaQuality) : MediaSettingsUiAction
    data class SetAutoSaveIncomingMedia(val enabled: Boolean) : MediaSettingsUiAction
    data class SetAutoSaveOnUnmeteredOnly(val enabled: Boolean) : MediaSettingsUiAction
    data class SetMirrorFrontCamera(val enabled: Boolean) : MediaSettingsUiAction
}
