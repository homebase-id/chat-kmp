package id.homebase.core.pdf

data class PdfThumbnailResult(
    val thumbnailBytes: ByteArray?,
    val pageCount: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false
        other as PdfThumbnailResult
        if (thumbnailBytes != null) {
            if (other.thumbnailBytes == null) return false
            if (!thumbnailBytes.contentEquals(other.thumbnailBytes)) return false
        } else if (other.thumbnailBytes != null) return false
        return pageCount == other.pageCount
    }

    override fun hashCode(): Int {
        var result = thumbnailBytes?.contentHashCode() ?: 0
        result = 31 * result + pageCount
        return result
    }
}

expect fun generatePdfThumbnail(bytes: ByteArray, maxWidth: Int): PdfThumbnailResult?
