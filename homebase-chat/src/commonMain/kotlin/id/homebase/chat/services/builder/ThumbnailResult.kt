package id.homebase.chat.services.builder

import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb

// Small shared result type
data class ThumbnailResult(
    val preview: EmbeddedThumb?,
    val thumbnails: List<ThumbnailFile>
)