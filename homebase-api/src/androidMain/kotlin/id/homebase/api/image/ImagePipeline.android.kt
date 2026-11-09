package id.homebase.api.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import androidx.exifinterface.media.ExifInterface
import co.touchlab.kermit.Logger
import id.homebase.api.lib.image.ImageFormatDetector
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlin.math.max

private class AndroidPipelineBitmap(private val bitmap: Bitmap) : PipelineBitmap {
    override val width get() = bitmap.width
    override val height get() = bitmap.height
    override val isSrgb get() = bitmap.colorSpace.let { it == null || it == ColorSpace.get(ColorSpace.Named.SRGB) }

    override fun scaledTo(width: Int, height: Int): PipelineBitmap =
        AndroidPipelineBitmap(Bitmap.createScaledBitmap(bitmap, width, height, true))

    // Not getPixels, which unpremultiplies and converts to sRGB, and would turn a P3 photo into sRGB.
    override fun readPixels(): ByteArray {
        val eightBit = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val pixels = ByteArray(eightBit.width * eightBit.height * 4)
        eightBit.copyPixelsToBuffer(ByteBuffer.wrap(pixels))
        if (eightBit !== bitmap) eightBit.recycle()
        return pixels
    }

    override fun withPixels(pixels: ByteArray, width: Int, height: Int): PipelineBitmap {
        val colorSpace = bitmap.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, bitmap.hasAlpha(), colorSpace)
        out.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
        return AndroidPipelineBitmap(out)
    }

    // compress reports failure by writing nothing, and an empty result would pass any byte limit.
    override fun encode(format: ImageFormat, quality: Int): ByteArray =
        ImageUtils.encodeBitmap(bitmap, format, quality).also { check(it.isNotEmpty()) { "Failed to encode image as $format" } }

    override fun inSrgb(): PipelineBitmap {
        if (isSrgb) return this
        val srgb = ColorSpace.get(ColorSpace.Named.SRGB)
        val converted = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888, bitmap.hasAlpha(), srgb)
        Canvas(converted).drawBitmap(bitmap, 0f, 0f, null)
        return AndroidPipelineBitmap(converted)
    }

    override fun close() = bitmap.recycle()
}

private const val TAG = "ImagePipeline"

private class HeaderRead : RuntimeException(null, null, false, false)

/**
 * ImageDecoder applies EXIF rotation and HEIF `irot` itself, and reads HEIC without a JPEG bridge.
 * BitmapFactory stays as the fallback for whatever it refuses.
 */
private class AndroidImageSource(private val bytes: ByteArray) : ImageSource {
    override val displayedSize: ImageSize = readWithDecoder() ?: readWithBitmapFactory()

    private fun readWithDecoder(): ImageSize? {
        var size: ImageSize? = null
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { _, info, _ ->
                size = ImageSize(info.size.width, info.size.height)
                throw HeaderRead() // Stops before any pixels are allocated.
            }
        } catch (_: HeaderRead) {
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "ImageDecoder couldn't read the header" }
        }
        return size
    }

    private fun readWithBitmapFactory(): ImageSize {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "Failed to read image size" }
        val rotation = ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees
        return if (rotation == 90 || rotation == 270) {
            ImageSize(options.outHeight, options.outWidth)
        } else {
            ImageSize(options.outWidth, options.outHeight)
        }
    }

    override fun decode(minLongEdge: Int): PipelineBitmap {
        // Only a JPEG decoder averages when it samples (DCT scaling). Every other format here picks
        // every Nth pixel, which is the aliasing this pipeline exists to remove.
        val sampleSize = if (ImageFormatDetector.detectFormat(bytes) == "image/jpeg") {
            sampleSizeFor(max(displayedSize.pixelWidth, displayedSize.pixelHeight), minLongEdge)
        } else {
            1
        }
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, _, _ ->
                decoder.setTargetSampleSize(sampleSize)
                // A HARDWARE bitmap has no readable pixels, so compress would fail on it.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "ImageDecoder refused the image; trying BitmapFactory" }
            ImageUtils.decodeBitmap(bytes, sampleSize)
        }
        return AndroidPipelineBitmap(bitmap)
    }

    override fun close() = Unit
}

/** The biggest power of two that still leaves at least [minLongEdge]; the pipeline scales the rest. */
private fun sampleSizeFor(longEdge: Int, minLongEdge: Int): Int {
    var sample = 1
    while (longEdge / (sample * 2) >= minLongEdge) sample *= 2
    return sample
}

internal actual fun openImageSource(bytes: ByteArray): ImageSource = AndroidImageSource(bytes)
