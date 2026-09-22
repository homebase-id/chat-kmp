package id.homebase.core.ui.screens.media

import id.homebase.api.image.MediaQuality
import id.homebase.resources.MR
import id.homebase.resources.settings_media_quality_high
import id.homebase.resources.settings_media_quality_high_description
import id.homebase.resources.settings_media_quality_standard
import id.homebase.resources.settings_media_quality_standard_description
import org.jetbrains.compose.resources.StringResource

data class MediaSettingsUiState(
    val mediaQuality: MediaQuality = MediaQuality.STANDARD,
    val autoSaveIncomingMedia: Boolean = false,
    val autoSaveOnUnmeteredOnly: Boolean = true,
)

sealed interface MediaSettingsUiAction {
    data class SetMediaQuality(val quality: MediaQuality) : MediaSettingsUiAction
    data class SetAutoSaveIncomingMedia(val enabled: Boolean) : MediaSettingsUiAction
    data class SetAutoSaveOnUnmeteredOnly(val enabled: Boolean) : MediaSettingsUiAction
}

val MediaQuality.label: StringResource
    get() = when (this) {
        MediaQuality.STANDARD -> MR.string.settings_media_quality_standard
        MediaQuality.HIGH -> MR.string.settings_media_quality_high
    }

val MediaQuality.description: StringResource
    get() = when (this) {
        MediaQuality.STANDARD -> MR.string.settings_media_quality_standard_description
        MediaQuality.HIGH -> MR.string.settings_media_quality_high_description
    }
