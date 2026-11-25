package id.homebase.core.image

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import id.homebase.api.file.contentTypeFromBytes
import id.homebase.api.image.scaledForDisplay
import okio.IOException
import okio.use
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.impl.use

/**
 * Coil's decode for still JPEG, PNG, WebP, GIF and BMP on desktop and iOS, shrunk by [decodeForDisplay].
 * Register after the animated and platform decoders: it takes every still image they leave.
 */
class DisplayImageDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult {
        val bytes = source.source().use { it.readByteArray() }
        return Image.makeFromEncoded(bytes).use { decodeForDisplay(it, options) }
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? =
            if (isSkiaStillSource(result)) DisplayImageDecoder(result.source, options) else null
    }
}

/**
 * [image] at the size Coil asks for, shrunk with the uploads' Lanczos: Coil's own Skia decode samples
 * nearest-neighbour, which leaves jagged edges. Leaves [image] open.
 */
suspend fun decodeForDisplay(image: Image, options: Options): DecodeResult {
    val (width, height) = decodeOutputSize(
        image.width, image.height, options.size, options.scale, options.maxBitmapSize, options.precision,
    )
    val shrinks = width < image.width || height < image.height
    val scaled = if (shrinks) scaledForDisplay(image, width, height) else image
    val bitmap = Bitmap()
    try {
        check(bitmap.allocPixels(ImageInfo.makeN32(width, height, ColorAlphaType.PREMUL, scaled.colorSpace))) {
            "Couldn't allocate a ${width}x$height bitmap"
        }
        if (scaled.width == width && scaled.height == height) {
            check(scaled.readPixels(bitmap, 0, 0)) { "Couldn't decode the image's pixels" }
        } else {
            // Only an exact-size request enlarges; Lanczos here is for shrinking.
            Canvas(bitmap).use { canvas ->
                canvas.drawImageRect(
                    scaled,
                    Rect.makeWH(scaled.width.toFloat(), scaled.height.toFloat()),
                    Rect.makeWH(width.toFloat(), height.toFloat()),
                    SamplingMode.MITCHELL,
                    null,
                    true,
                )
            }
        }
    } finally {
        if (scaled !== image) scaled.close()
    }
    bitmap.setImmutable()
    return DecodeResult(image = bitmap.asImage(), isSampled = shrinks)
}

private val SKIA_STILL_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp")
private const val HEADER_BYTES = 32L

private fun isSkiaStillSource(result: SourceFetchResult): Boolean = try {
    val peek = result.source.source().peek()
    peek.request(HEADER_BYTES)
    contentTypeFromBytes(peek.readByteArray(minOf(HEADER_BYTES, peek.buffer.size))) in SKIA_STILL_TYPES
} catch (_: IOException) {
    false
}
