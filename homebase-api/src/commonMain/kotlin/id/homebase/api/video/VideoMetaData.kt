package id.homebase.homebasekmppoc.prototype.lib.video

import kotlinx.serialization.Serializable

@Serializable
data class VideoMetaData (
    val mimeType: String? = null,
    val isSegmented: Boolean = false,
    val fileSize: Long? = null,
    val key: String? = null,
    val duration: Double? = null,
    val codec: String? = null,
    val hlsPlaylist: String? = null,
)
