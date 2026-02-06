package id.homebase.api.video

data class VideoMetadata(
    val mimeType: String,
    val isSegmented: Boolean,
    val fileSize: Long,
    val durationMs: Long,
    val isDescriptorContentComplete: Boolean = true,
    val key: String? = null
)