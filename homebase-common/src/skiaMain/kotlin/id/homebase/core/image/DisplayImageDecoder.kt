package id.homebase.core.image

import co.touchlab.kermit.Logger
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import id.homebase.api.file.contentTypeFromBytes
import id.homebase.api.image.ImageSize
import id.homebase.api.image.convertHeicToJpeg
import id.homebase.api.image.decodeForDisplay
import id.homebase.api.lib.image.ImageFormatDetector
import okio.use
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.impl.use
import kotlin.coroutines.cancellation.CancellationException

/**
 * Coil's Skia decode samples nearest-neighbour when it shrinks, so this decodes and shrinks as the uploads do.
 * Register it after the animated and platform decoders: it takes every still image they leave.
 */
class DisplayImageDecoder(
    private val source: ImageSource,
    private val options: Options,
    private val heic: Boolean,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        try {
            // The pipeline converts HEIC it recognises by major brand; one claimed only by its declared type is converted here.
            val decodable = if (heic && !ImageFormatDetector.isHeic(bytes)) {
                convertHeicToJpeg(bytes) ?: error("Couldn't convert the HEIC to JPEG")
            } else {
                bytes
            }
            return decode(decodable)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No stack trace: Coil asks again each time a broken image scrolls back into view.
            Logger.w(tag = TAG) { "Couldn't decode a ${bytes.size}-byte ${if (heic) "HEIC" else "image"} for display: $e" }
            throw e
        }
    }

    private suspend fun decode(bytes: ByteArray): DecodeResult {
        lateinit var sourceSize: ImageSize
        lateinit var outSize: ImageSize
        val image = decodeForDisplay(bytes) { size ->
            sourceSize = size
            decodeOutputSize(size.pixelWidth, size.pixelHeight, options.size, options.scale, options.maxBitmapSize, options.precision)
                .let { (width, height) -> ImageSize(width, height) }
                .also { outSize = it }
        }
        val bitmap = image.use { it.toBitmap(outSize.pixelWidth, outSize.pixelHeight) }
        val isSampled = outSize.pixelWidth < sourceSize.pixelWidth || outSize.pixelHeight < sourceSize.pixelHeight
        return DecodeResult(image = bitmap.asImage(), isSampled = isSampled)
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val header = result.peekHeader(HEADER_BYTES) ?: return null
            return when {
                contentTypeFromBytes(header) in STILL_TYPES -> DisplayImageDecoder(result.source, options, heic = false)
                // Includes an AVIF-compatible mif1 file, which sniffs as AVIF.
                isHeicSource(result) -> DisplayImageDecoder(result.source, options, heic = true)
                else -> null
            }
        }
    }
}

private fun Image.toBitmap(width: Int, height: Int): Bitmap {
    val bitmap = Bitmap()
    try {
        check(bitmap.allocPixels(ImageInfo.makeN32(width, height, ColorAlphaType.PREMUL, colorSpace))) {
            "Couldn't allocate a ${width}x$height bitmap"
        }
        if (this.width == width && this.height == height) {
            check(readPixels(bitmap, 0, 0)) { "Couldn't read the image's pixels" }
        } else {
            // Only an exact-size request enlarges, and Lanczos is for shrinking.
            Canvas(bitmap).use { canvas ->
                canvas.drawImageRect(
                    this,
                    Rect.makeWH(this.width.toFloat(), this.height.toFloat()),
                    Rect.makeWH(width.toFloat(), height.toFloat()),
                    SamplingMode.MITCHELL,
                    null,
                    true,
                )
            }
        }
    } catch (e: Throwable) {
        bitmap.close()
        throw e
    }
    bitmap.setImmutable()
    return bitmap
}

private const val TAG = "DisplayImageDecoder"
private val STILL_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp")
private const val HEADER_BYTES = 32L
