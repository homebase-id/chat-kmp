package id.homebase.api.video

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class VideoMetadata(
    val mimeType: String,
    val isDescriptorContentComplete: Boolean = true,
    val isSegmented: Boolean,
    val fileSize: Long = 0L,
    val duration: Float = 0F,
    val key: String = "",
    val codec: String = if (isSegmented) "video/mp2t" else "video/mp4",
    val hlsPlaylist: String? = null,
    // Real technical metadata captured at encode time (probe of the compressed
    // output). 0 / false = unknown / not probed (older payloads, probe failure).
    // Surfaced by the inline debug overlay; see DescriptorContent.VideoFile.
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val bitDepth: Int = 0,
    val isHdr: Boolean = false,
    val videoBitrateBps: Long = 0L,
)
