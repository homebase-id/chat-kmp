package id.homebase.api.client.drives.files

import id.homebase.api.client.drives.upload.EmbeddedThumb
import kotlinx.serialization.Serializable

/**
 * Base payload file for upload. Ported from TypeScript BasePayloadFile interface. Note: Uses
 * ByteArray for payload instead of File/Blob.
 */
@Serializable
data class PayloadFile(
    val key: String,
    // Per GPT - There is no single stream abstraction that works across JVM, Android, iOS, and JS and is safe to serialize or reuse.
    // So filePath is the only way to hand in a payload
    val filePath: String,
    val previewThumbnail: EmbeddedThumb? = null,
    val contentType: String = "",
    val descriptorContent: String? = null,
    val isPreEncrypted: Boolean = false,
    /** IV for manual encryption mode (when skipEncryption = true). */
    val iv: ByteArray? = null,
    /** Video-only: trim start, ms, applied during compression. Null = no trim. */
    val trimStartMs: Long? = null,
    /** Video-only: trim end, ms, applied during compression. Null = no trim. */
    val trimEndMs: Long? = null,
    /**
     * Web-only, video-only: a `blob:` object URL for the original picked file, used as the ffmpeg
     * compress INPUT so the bytes are read in JS (no Kotlin copy / base64). Null elsewhere. Not part
     * of [equals]/[hashCode] — it's a transient input handle, like [trimStartMs]/[trimEndMs].
     */
    val inputBlobUrl: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as PayloadFile

        if (key != other.key) return false
        if (!filePath.contentEquals(other.filePath)) return false
        if (previewThumbnail != other.previewThumbnail) return false
        if (descriptorContent != other.descriptorContent) return false
        if (isPreEncrypted != other.isPreEncrypted) return false
        if (iv != null) {
            if (other.iv == null) return false
            if (!iv.contentEquals(other.iv)) return false
        } else if (other.iv != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = key.hashCode()
        result = 31 * result + filePath.hashCode()
        result = 31 * result + (previewThumbnail?.hashCode() ?: 0)
        result = 31 * result + (descriptorContent?.hashCode() ?: 0)
        result = 31 * result + isPreEncrypted.hashCode()
        result = 31 * result + (iv?.contentHashCode() ?: 0)
        return result
    }
}