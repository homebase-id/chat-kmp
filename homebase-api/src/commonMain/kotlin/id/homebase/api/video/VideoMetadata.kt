package id.homebase.api.video

import kotlinx.serialization.Serializable

@Serializable
data class VideoMetadata(
    val mimeType: String,
    val isDescriptorContentComplete: Boolean = true,
    val isSegmented: Boolean,
    val fileSize: Long,
    val duration: Long,
    val key: String,
    val codec: String,
    val hlsPlaylist: String? = null,
)