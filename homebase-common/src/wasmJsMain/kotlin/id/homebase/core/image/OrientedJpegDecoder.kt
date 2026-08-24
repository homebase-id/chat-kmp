package id.homebase.core.image

import co.touchlab.kermit.Logger
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Precision
import coil3.size.Size
import coil3.size.pxOrElse
import id.homebase.api.image.jpegOrientationSwapsDimensions
import id.homebase.api.image.readJpegExifOrientation
import okio.use
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.impl.use

/**
 * Web-only Coil3 [Decoder] for JPEGs whose EXIF orientation is a quarter turn
 * (5-8), decoding them through Skia instead of Coil's browser path.
 *
 * Coil's wasmJs decoder sizes its decode from the JPEG *frame header*
 * (`getJpegSizeOrNull`), which reports the stored dimensions with EXIF
 * orientation NOT applied, then hands those to
 * `createImageBitmap(blob, { resizeWidth, resizeHeight })`. The browser applies
 * EXIF orientation while decoding (`imageOrientation: "from-image"` is the spec
 * default), so an orientation 5-8 photo comes back transposed and is then
 * force-resized into the untransposed box — a non-uniform stretch. A 600x450
 * photo tagged orientation 6 renders squeezed into 450x600. Every other target
 * decodes via `Image.makeFromEncoded`, which applies the orientation to both
 * pixels and dimensions, so this only ever showed on web.
 *
 * Cost: this decode is synchronous on the browser's single thread, unlike
 * Coil's worker path. Limited to the orientations that are actually broken so
 * screenshots, downloads and already-upright photos keep the async path.
 */
class OrientedJpegDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        val image = Image.makeFromEncoded(bytes)
        try {
            val srcWidth = image.width
            val srcHeight = image.height
            var multiplier = DecodeUtils.computeSizeMultiplier(
                srcWidth = srcWidth,
                srcHeight = srcHeight,
                dstWidth = options.size.width.pxOrElse { srcWidth },
                dstHeight = options.size.height.pxOrElse { srcHeight },
                scale = options.scale,
                maxSize = Size.ORIGINAL,
            )
            if (options.precision == Precision.INEXACT) {
                multiplier = multiplier.coerceAtMost(1.0)
            }
            val outWidth = (multiplier * srcWidth).toInt().coerceAtLeast(1)
            val outHeight = (multiplier * srcHeight).toInt().coerceAtLeast(1)

            val bitmap = Bitmap()
            bitmap.allocN32Pixels(outWidth, outHeight)
            Canvas(bitmap).use { canvas ->
                canvas.drawImageRect(
                    image = image,
                    src = Rect.makeWH(srcWidth.toFloat(), srcHeight.toFloat()),
                    dst = Rect.makeWH(outWidth.toFloat(), outHeight.toFloat()),
                )
            }
            bitmap.setImmutable()
            return DecodeResult(
                image = bitmap.asImage(),
                isSampled = outWidth < srcWidth || outHeight < srcHeight,
            )
        } finally {
            image.close()
        }
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            val head = try {
                val peek = result.source.source().peek()
                // request() fills the peek buffer up to n; a plain read() would
                // hand back a single segment (8 KiB) and cut EXIF short.
                peek.request(EXIF_SCAN_BYTES)
                peek.readByteArray(minOf(EXIF_SCAN_BYTES, peek.buffer.size))
            } catch (e: Throwable) {
                Logger.d(tag = TAG) { "peek read failed: ${e.message}" }
                return null
            }
            val orientation = readJpegExifOrientation(head) ?: return null
            if (!jpegOrientationSwapsDimensions(orientation)) return null
            return OrientedJpegDecoder(result.source, options)
        }
    }

    private companion object {
        private const val TAG = "OrientedJpegDecoder"

        /** EXIF rides in the first APP1 segment, so the head of the file is enough. */
        private const val EXIF_SCAN_BYTES = 64L * 1024
    }
}
