package id.homebase.api.video

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class VideoMetadata(
    val mimeType: String,
    val isDescriptorContentComplete: Boolean = true,
    val isSegmented: Boolean,
    val fileSize: Long,
    val duration: Float = 0F,
    val key: String = "",
    val codec: String,
    val hlsPlaylist: String? = null,
)
