package id.homebase.imageeditor.core.io

import id.homebase.api.image.ImageFormat
import id.homebase.api.image.ImageUtils
import id.homebase.imageeditor.core.Size

/**
 * Bytes + metadata produced by [CropPreprocessor.prepare].
 *
 * - [originalBytes] is kept verbatim for the lossless final crop.
 * - [previewBytes] is the EXIF-corrected, downsampled image bytes used by the
 *   UI while the user adjusts the crop. UI converts to `ImageBitmap`.
 * - [naturalSize] is the EXIF-corrected pixel size of the source image.
 */
class CropPreprocessorResult(
    val originalBytes: ByteArray,
    val previewBytes: ByteArray,
    val naturalSize: Size,
)

/**
 * Decode source image bytes into an EXIF-corrected, downsampled
 * [ImageBitmap] suitable for showing under the cropper overlay.
 *
 * The original bytes are returned alongside so the finalizer can warp from
 * the lossless source rather than the downsampled preview.
 */
object CropPreprocessor {

    /** Maximum edge length of the preview bitmap, in pixels. */
    const val DEFAULT_PREVIEW_MAX_EDGE: Int = 2048

    fun prepare(
        srcBytes: ByteArray,
        previewMaxEdge: Int = DEFAULT_PREVIEW_MAX_EDGE,
    ): CropPreprocessorResult? {
        // EXIF-correct natural size by decoding-then-resizing rather than
        // calling getNaturalSize (which on Android returns RAW dims).
        val previewResult = try {
            ImageUtils.resizePreserveAspect(
                srcBytes = srcBytes,
                maxWidth = previewMaxEdge,
                maxHeight = previewMaxEdge,
                outputFormat = ImageFormat.PNG, // lossless preview
                quality = 100,
            )
        } catch (_: Exception) {
            return null
        }

        val natural = previewResult.naturalSize
        return CropPreprocessorResult(
            originalBytes = srcBytes,
            previewBytes = previewResult.bytes,
            naturalSize = Size(natural.pixelWidth, natural.pixelHeight),
        )
    }
}
