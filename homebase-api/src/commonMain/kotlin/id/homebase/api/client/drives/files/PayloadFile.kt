package id.homebase.homebasekmppoc.prototype.lib.drives.files

import id.homebase.homebasekmppoc.prototype.lib.drives.upload.EmbeddedThumb
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
    val previewThumbnail: id.homebase.homebasekmppoc.prototype.lib.drives.upload.EmbeddedThumb? = null,
    val contentType: String = "",
    val descriptorContent: String? = null,
    val skipEncryption: Boolean = false,
    /** IV for manual encryption mode (when skipEncryption = true). */
    val iv: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as id.homebase.homebasekmppoc.prototype.lib.drives.files.PayloadFile

        if (key != other.key) return false
        if (!filePath.contentEquals(other.filePath)) return false
        if (previewThumbnail != other.previewThumbnail) return false
        if (descriptorContent != other.descriptorContent) return false
        if (skipEncryption != other.skipEncryption) return false
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
        result = 31 * result + skipEncryption.hashCode()
        result = 31 * result + (iv?.contentHashCode() ?: 0)
        return result
    }
}