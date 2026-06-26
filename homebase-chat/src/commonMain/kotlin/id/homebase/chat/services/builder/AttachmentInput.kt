package id.homebase.chat.services.builder

// UI-safe attachment input (no keys, no crypto, no thumbnails)
data class AttachmentInput(
    val filePath: String,
    val contentType: String,
    val displayName: String? = null,
    val waveformFile: String? = null,
    val audioLengthSeconds: Int? = null,
    /** Video-only: optional trim applied during compression. */
    val trimStartMs: Long? = null,
    val trimEndMs: Long? = null,
    /**
     * Web-only, video-only: a `blob:` object URL for the picked file, threaded to the ffmpeg
     * compress INPUT so the original is read in JS (no Kotlin copy / base64). Null on native.
     */
    val inputBlobUrl: String? = null,
    /**
     * Image-only: send this attachment as a sticker (transparent cut-out render). This is
     * the ONLY way an attachment becomes a sticker — the "Send as sticker" toggle, the
     * sticker tool, and the background-remover set it. There is no transparency
     * auto-detection: a shared/normal image is never stickered without this opt-in (#854).
     */
    val forceSticker: Boolean = false,
)