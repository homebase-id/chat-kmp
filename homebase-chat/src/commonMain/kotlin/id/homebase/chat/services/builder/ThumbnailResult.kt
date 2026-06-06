package id.homebase.chat.services.builder

import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb

// Small shared result type
data class ThumbnailResult(
    val preview: EmbeddedThumb?,
    val thumbnails: List<ThumbnailFile>,
    /**
     * The ORIGINAL (pre-thumbnail) source bytes the generator already read.
     * Threaded out so the attachment builder can run a sticker/alpha probe on
     * the source — not the re-encoded thumbnail (lossy re-encode adds an alpha
     * fringe) — without a second file read.
     */
    val sourceBytes: ByteArray
) {
    // ByteArray uses identity equals/hashCode by default, which would make two
    // structurally-equal results unequal. Override so the data class stays sane.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ThumbnailResult) return false
        return preview == other.preview &&
            thumbnails == other.thumbnails &&
            sourceBytes.contentEquals(other.sourceBytes)
    }

    override fun hashCode(): Int {
        var result = preview?.hashCode() ?: 0
        result = 31 * result + thumbnails.hashCode()
        result = 31 * result + sourceBytes.contentHashCode()
        return result
    }
}