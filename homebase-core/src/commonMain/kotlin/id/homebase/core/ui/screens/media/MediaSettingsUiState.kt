package id.homebase.core.ui.screens.media

data class MediaSettingsUiState(
    val mirrorFrontCamera: Boolean = true,
)

sealed interface MediaSettingsUiAction {
    data class SetMirrorFrontCamera(val enabled: Boolean) : MediaSettingsUiAction
}
