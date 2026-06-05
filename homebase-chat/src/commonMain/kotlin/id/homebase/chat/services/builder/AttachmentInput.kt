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
     * Image-only: force this attachment to be sent as a sticker (transparent cut-out
     * render), skipping the automatic alpha probe. v1 leaves this `false` and relies on
     * auto-detection; the future manual "Send as sticker" toggle flips this without any
     * further change to the attachment builder.
     */
    val forceSticker: Boolean = false,
)