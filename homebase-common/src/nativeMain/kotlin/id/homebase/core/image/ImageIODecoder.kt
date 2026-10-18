package id.homebase.core.image

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.maxBitmapSize
import okio.FileSystem
import okio.use
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Rect
import org.jetbrains.skia.impl.use

/**
 * Coil3 Decoder for camera photos (HEIC/HEIF, which Skia can't read, and JPEG) on iOS.
 *
 * ImageIO decodes straight to the requested size, so a 12 MP photo shown as a 60dp
 * thumbnail never allocates its ~48 MB full-resolution bitmap. Skia's decoder and
 * UIImage both decode at full size first, which let a batch of picked photos exhaust memory.
 *
 * Register before SkiaImageDecoder so it gets first crack at these sources.
 */
class ImageIODecoder(
    private val source: ImageSource,
    private val options: Options,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        var srcW = 0
        var srcH = 0
        var outW = 0
        var outH = 0
        val maxPixelSizeFor = { width: Int, height: Int ->
            srcW = width
            srcH = height
            val out = decodeOutputSize(width, height, options.size, options.scale, options.maxBitmapSize, options.precision)
            outW = out.first
            outH = out.second
            maxOf(outW, outH)
        }
        val file = source.fileOrNull()?.takeIf { source.fileSystem == FileSystem.SYSTEM }
        val image = if (file != null) {
            NativeImageDecoder.decodeThumbnail(file.toString(), maxPixelSizeFor)
        } else {
            NativeImageDecoder.decodeThumbnail(source.source().use { it.readByteArray() }, maxPixelSizeFor)
        } ?: return null

        val bitmap = Bitmap()
        if (!bitmap.allocN32Pixels(outW, outH)) {
            image.close()
            return null
        }
        Canvas(bitmap).use { canvas ->
            canvas.drawImageRect(
                image,
                Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                Rect.makeWH(outW.toFloat(), outH.toFloat()),
            )
        }
        bitmap.setImmutable()
        image.close()
        return DecodeResult(image = bitmap.asImage(), isSampled = outW < srcW || outH < srcH)
    }

    class Factory : Decoder.Factory {
        override fun create(
            result: SourceFetchResult,
            options: Options,
            imageLoader: ImageLoader,
        ): Decoder? {
            if (!isHeicSource(result) && !isJpegSource(result)) return null
            return ImageIODecoder(result.source, options)
        }
    }
}

private fun isJpegSource(result: SourceFetchResult): Boolean {
    if (result.mimeType == "image/jpeg") return true
    return try {
        val header = result.source.source().peek().readByteArray(3)
        header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte() && header[2] == 0xFF.toByte()
    } catch (_: Exception) {
        false
    }
}
