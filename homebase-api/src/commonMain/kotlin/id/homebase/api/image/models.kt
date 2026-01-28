package id.homebase.api.image

data class ImageSize(val pixelWidth: Int, val pixelHeight: Int)

data class ImageResult(
    val bytes: ByteArray,
    val naturalSize: ImageSize, // original image size
    val size: ImageSize // resulting (updated) image size
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as ImageResult

        if (!bytes.contentEquals(other.bytes)) return false
        if (naturalSize != other.naturalSize) return false
        if (size != other.size) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bytes.contentHashCode()
        result = 31 * result + naturalSize.hashCode()
        result = 31 * result + size.hashCode()
        return result
    }
}

data class ThumbnailInstruction(
    val quality: Int,
    val maxPixelDimension: Int,
    val maxBytes: Int,
    val type: ImageFormat = ImageFormat.WEBP
)

enum class ImageFormat {
    WEBP, JPEG, PNG, BMP, GIF
}

