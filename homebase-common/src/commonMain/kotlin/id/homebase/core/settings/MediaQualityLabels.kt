package id.homebase.core.settings

import id.homebase.api.image.MediaQuality
import id.homebase.resources.MR
import id.homebase.resources.settings_media_quality_hd
import id.homebase.resources.settings_media_quality_hd_description
import id.homebase.resources.settings_media_quality_original
import id.homebase.resources.settings_media_quality_original_description
import id.homebase.resources.settings_media_quality_standard
import id.homebase.resources.settings_media_quality_standard_description
import org.jetbrains.compose.resources.StringResource

val MediaQuality.label: StringResource
    get() = when (this) {
        MediaQuality.STANDARD -> MR.string.settings_media_quality_standard
        MediaQuality.HD -> MR.string.settings_media_quality_hd
        MediaQuality.ORIGINAL -> MR.string.settings_media_quality_original
    }

val MediaQuality.description: StringResource
    get() = when (this) {
        MediaQuality.STANDARD -> MR.string.settings_media_quality_standard_description
        MediaQuality.HD -> MR.string.settings_media_quality_hd_description
        MediaQuality.ORIGINAL -> MR.string.settings_media_quality_original_description
    }
