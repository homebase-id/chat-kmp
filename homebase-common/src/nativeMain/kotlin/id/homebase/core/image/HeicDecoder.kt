package id.homebase.core.image

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.DecodeUtils
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import coil3.size.Precision
import okio.use
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Rect
import org.jetbrains.skia.impl.use

/**
 * Coil3 Decoder that handles HEIC/HEIF images on iOS by decoding them
 * through native UIImage APIs instead of Skia's makeFromEncoded (which
 * does not support HEIC).
 *
 * Register before SkiaImageDecoder so it gets first crack at HEIC sources.
 */
class HeicDecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val bytes = source.source().use { it.readByteArray() }
        val image = NativeImageDecoder.decode(bytes) ?: return null

        val srcW = image.width
        val srcH = image.height
        @OptIn(coil3.annotation.ExperimentalCoilApi::class)
        val dstSize = DecodeUtils.computeDstSize(srcW, srcH, options.size, options.scale, options.maxBitmapSize)
        @OptIn(coil3.annotation.ExperimentalCoilApi::class)
        var multiplier = DecodeUtils.computeSizeMultiplier(
            srcW, srcH, dstSize.first, dstSize.second, options.scale, options.maxBitmapSize,
        )
        if (options.precision == Precision.INEXACT) multiplier = multiplier.coerceAtMost(1.0)

        val outW = (multiplier * srcW).toInt()
        val outH = (multiplier * srcH).toInt()

        val bitmap = Bitmap()
        if (!bitmap.allocN32Pixels(outW, outH)) {
            image.close()
            return null
        }
        Canvas(bitmap).use { canvas ->
            canvas.drawImageRect(
                image,
                Rect.makeWH(srcW.toFloat(), srcH.toFloat()),
                Rect.makeWH(outW.toFloat(), outH.toFloat()),
            )
        }
        bitmap.setImmutable()
        val isSampled = outW < srcW || outH < srcH
        image.close()
        return DecodeResult(image = bitmap.asImage(), isSampled = isSampled)
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            if (!isHeicSource(result)) return null
            return HeicDecoder(result.source, options)
        }
    }
}
