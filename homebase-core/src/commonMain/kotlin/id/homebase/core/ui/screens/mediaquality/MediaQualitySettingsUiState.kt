package id.homebase.core.ui.screens.mediaquality

import id.homebase.api.image.MediaQuality
import id.homebase.resources.MR
import id.homebase.resources.settings_media_quality_high
import id.homebase.resources.settings_media_quality_standard
import org.jetbrains.compose.resources.StringResource

data class MediaQualitySettingsUiState(
    val mediaQuality: MediaQuality = MediaQuality.STANDARD,
)

sealed interface MediaQualitySettingsUiAction {
    data class SetMediaQuality(val quality: MediaQuality) : MediaQualitySettingsUiAction
}

val MediaQuality.label: StringResource
    get() = when (this) {
        MediaQuality.STANDARD -> MR.string.settings_media_quality_standard
        MediaQuality.HIGH -> MR.string.settings_media_quality_high
    }
