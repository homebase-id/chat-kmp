package id.homebase.api.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlin.math.max

private class AndroidPipelineBitmap(private val bitmap: Bitmap) : PipelineBitmap {
    override val width get() = bitmap.width
    override val height get() = bitmap.height

    override fun scaledTo(width: Int, height: Int): PipelineBitmap =
        AndroidPipelineBitmap(Bitmap.createScaledBitmap(bitmap, width, height, true))

    override fun encode(format: ImageFormat, quality: Int): ByteArray =
        ImageUtils.encodeBitmap(bitmap, format, quality)

    override fun close() = bitmap.recycle()
}

private class HeaderRead : RuntimeException(null, null, false, false)

/**
 * ImageDecoder applies EXIF rotation and HEIF `irot` itself, and reads HEIC without a JPEG bridge.
 * BitmapFactory stays as the fallback for whatever it refuses.
 */
private class AndroidImageSource(private val bytes: ByteArray) : ImageSource {
    override val displayedSize: ImageSize by lazy { readWithDecoder() ?: readWithBitmapFactory() }

    private fun readWithDecoder(): ImageSize? {
        var size: ImageSize? = null
        try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { _, info, _ ->
                size = ImageSize(info.size.width, info.size.height)
                throw HeaderRead() // Stops before any pixels are allocated.
            }
        } catch (_: HeaderRead) {
        } catch (_: Exception) {
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
        val sampleSize = if (bytes.isJpeg()) {
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
        } catch (_: Exception) {
            decodeWithBitmapFactory(sampleSize)
        }
        return AndroidPipelineBitmap(bitmap)
    }

    private fun decodeWithBitmapFactory(sampleSize: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleSize
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("Failed to decode image bytes")
        return ImageUtils.applyExifOrientation(bitmap, bytes)
    }

    override fun close() = Unit
}

private fun ByteArray.isJpeg() = size >= 3 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte() && this[2] == 0xFF.toByte()

/** The biggest power of two that still leaves at least [minLongEdge]; the pipeline scales the rest. */
private fun sampleSizeFor(longEdge: Int, minLongEdge: Int): Int {
    var sample = 1
    while (longEdge / (sample * 2) >= minLongEdge) sample *= 2
    return sample
}

internal actual fun openImageSource(bytes: ByteArray): ImageSource = AndroidImageSource(bytes)
