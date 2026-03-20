package id.homebase.chat.services.builder

// UI-safe attachment input (no keys, no crypto, no thumbnails)
data class AttachmentInput(
    val filePath: String,
    val contentType: String,
    val displayName: String? = null,
    val waveformFile: String? = null,
    val audioLengthSeconds: Int? = null,
)