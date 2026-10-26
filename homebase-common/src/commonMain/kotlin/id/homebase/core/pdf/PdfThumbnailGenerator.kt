package id.homebase.core.pdf

/** [thumbnailBytes] is a JPEG of the first page; [pageCount] is the page count of the whole document. */
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

/**
 * JPEG thumbnail of page one of the PDF in [bytes], [maxWidth] pixels wide with the page's
 * aspect ratio, at about 80% quality, plus the document's page count. Blocking: call off the
 * main thread. Returns null instead of throwing for anything unusable: empty or non-PDF
 * bytes, a PDF with no pages, a password-protected PDF, a [maxWidth] of 0 or less. Web has no
 * PDF engine and always returns null.
 */
expect fun generatePdfThumbnail(bytes: ByteArray, maxWidth: Int): PdfThumbnailResult?

/** As [generatePdfThumbnail] for the PDF at [filePath]; a missing or unreadable file also gives null. */
expect fun generatePdfThumbnailFromFile(filePath: String, maxWidth: Int): PdfThumbnailResult?
