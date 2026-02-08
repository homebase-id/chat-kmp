package id.homebase.api.client.drives.files

import id.homebase.api.image.ImageSize
import kotlinx.serialization.Serializable

@Serializable
data class ThumbnailFile(
    val pixelWidth: Int,
    val pixelHeight: Int,
    val thumbnailBytes: ByteArray, // raw bytes -> equivalent to Blob
    val key: String,
    val contentType: String = "image/webp",
    val quality: Int = 76,
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
        if (!thumbnailBytes.contentEquals(other.thumbnailBytes)) return false
        if (key != other.key) return false
        if (contentType != other.contentType) return false
        if (quality != other.quality) return false

        return true
    }

    override fun hashCode(): Int {
        var result = pixelWidth
        result = 31 * result + pixelHeight
        result = 31 * result + thumbnailBytes.contentHashCode()
        result = 31 * result + key.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + quality
        return result
    }
}