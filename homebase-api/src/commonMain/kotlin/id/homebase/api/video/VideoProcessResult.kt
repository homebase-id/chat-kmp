package id.homebase.api.video

import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb

data class VideoProcessResult(
    val payloads: List<PayloadFile>,
    val thumbnails: List<ThumbnailFile>,
    val tinyThumb: EmbeddedThumb?,
    val playlistPath: String,
    val videoMetadata: VideoMetadata
)