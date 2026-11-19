package id.homebase.core.settings

import id.homebase.api.image.MediaQuality
import id.homebase.resources.MR
import id.homebase.resources.photobase_backup_photo_standard_desc
import id.homebase.resources.photobase_backup_photo_hd_desc
import id.homebase.resources.photobase_backup_photo_original_desc
import id.homebase.resources.photobase_backup_video_standard_desc
import id.homebase.resources.photobase_backup_video_hd_desc
import id.homebase.resources.photobase_backup_video_original_desc
import id.homebase.resources.settings_media_quality_hd
import id.homebase.resources.settings_media_quality_original
import id.homebase.resources.settings_media_quality_standard
import org.jetbrains.compose.resources.StringResource

val MediaQuality.label: StringResource
    get() = when (this) {
        MediaQuality.STANDARD -> MR.string.settings_media_quality_standard
        MediaQuality.HD -> MR.string.settings_media_quality_hd
        MediaQuality.ORIGINAL -> MR.string.settings_media_quality_original
    }

val MediaQuality.photoDescription: StringResource
    get() = when (this) {
        MediaQuality.STANDARD -> MR.string.photobase_backup_photo_standard_desc
        MediaQuality.HD -> MR.string.photobase_backup_photo_hd_desc
        MediaQuality.ORIGINAL -> MR.string.photobase_backup_photo_original_desc
    }

val MediaQuality.videoDescription: StringResource
    get() = when (this) {
        MediaQuality.STANDARD -> MR.string.photobase_backup_video_standard_desc
        MediaQuality.HD -> MR.string.photobase_backup_video_hd_desc
        MediaQuality.ORIGINAL -> MR.string.photobase_backup_video_original_desc
    }
