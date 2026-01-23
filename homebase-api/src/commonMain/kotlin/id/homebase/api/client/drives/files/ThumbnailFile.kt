package id.homebase.api.client.drives.files

import id.homebase.api.image.ImageSize
import kotlinx.serialization.Serializable

@Serializable
data class ThumbnailFile(
    val pixelWidth: Int,
    val pixelHeight: Int,
    val payload: ByteArray, // raw bytes -> equivalent to Blob
    val key: String,
    val contentType: String = "image/webp",
    val quality: Int = 76,
    val skipEncryption: Boolean = false
) {
    // Convenience property to match test expectations
    val imageSize: ImageSize
        get() = ImageSize(pixelWidth, pixelHeight)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ThumbnailFile

        if (pixelWidth != other.pixelWidth) return false
        if (pixelHeight != other.pixelHeight) return false
        if (!payload.contentEquals(other.payload)) return false
        if (key != other.key) return false
        if (contentType != other.contentType) return false
        if (quality != other.quality) return false
        if (skipEncryption != other.skipEncryption) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pixelWidth
        result = 31 * result + pixelHeight
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + key.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + quality
        result = 31 * result + skipEncryption.hashCode()
        return result
    }
}