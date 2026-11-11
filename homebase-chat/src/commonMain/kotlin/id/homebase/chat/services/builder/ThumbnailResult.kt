package id.homebase.chat.services.builder

import id.homebase.api.image.ImageSize
import id.homebase.api.image.RenderedImage
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb

// Small shared result type
data class ThumbnailResult(
    val preview: EmbeddedThumb?,
    val thumbnails: List<ThumbnailFile>,
    /** The source file's detected type, so the builder needn't read the file again to ask. */
    val sourceFormat: String?,
    val sourceByteCount: Long?,
    /** What a Standard or HD send should carry instead of the file, made from the same decode. */
    val finalImage: RenderedImage? = null,
    val displayedSize: ImageSize? = null,
)
