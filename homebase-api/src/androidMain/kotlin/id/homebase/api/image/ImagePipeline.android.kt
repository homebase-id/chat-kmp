package id.homebase.api.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import co.touchlab.kermit.Logger
import id.homebase.api.ActivityProvider
import id.homebase.api.lib.image.ImageFormatDetector
import id.homebase.imageresize.NativeResize
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import kotlin.math.max

private class AndroidPipelineBitmap(private val bitmap: Bitmap) : PipelineBitmap {
    override val width get() = bitmap.width
    override val height get() = bitmap.height
    override val isSrgb get() = bitmap.colorSpace.let { it == null || it == ColorSpace.get(ColorSpace.Named.SRGB) }

    override fun scaledTo(width: Int, height: Int): PipelineBitmap =
        AndroidPipelineBitmap(Bitmap.createScaledBitmap(bitmap, width, height, true))

    // An OutOfMemoryError is an Error, so the send's fail-soft catch would miss it and the app would crash.
    override suspend fun resampledTo(width: Int, height: Int): PipelineBitmap = try {
        val eightBit = if (bitmap.config == Bitmap.Config.ARGB_8888) bitmap else checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false))
        try {
            AndroidPipelineBitmap(lanczos(eightBit, width, height))
        } finally {
            if (eightBit !== bitmap) eightBit.recycle()
        }
    } catch (e: OutOfMemoryError) {
        throw IllegalStateException("Not enough memory to scale the image", e)
    }

    private fun lanczos(source: Bitmap, width: Int, height: Int): Bitmap {
        val colorSpace = source.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, source.hasAlpha(), colorSpace)
        try {
            NativeResize.lanczos(source, out)
        } catch (e: Throwable) {
            out.recycle()
            throw e
        }
        return out
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

/** What an [AndroidImageSource] reads: bytes already in memory, or a file or content URI it opens itself. */
private sealed interface AndroidImageInput {
    fun decoderSource(): ImageDecoder.Source
    fun openStream(): InputStream

    class Bytes(val bytes: ByteArray) : AndroidImageInput {
        override fun decoderSource(): ImageDecoder.Source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        override fun openStream(): InputStream = ByteArrayInputStream(bytes)
    }

    class Path(val file: File) : AndroidImageInput {
        override fun decoderSource(): ImageDecoder.Source = ImageDecoder.createSource(file)
        override fun openStream(): InputStream = file.inputStream()
    }

    class Content(val resolver: ContentResolver, val uri: Uri) : AndroidImageInput {
        override fun decoderSource(): ImageDecoder.Source = ImageDecoder.createSource(resolver, uri)
        override fun openStream(): InputStream =
            resolver.openInputStream(uri) ?: throw IllegalArgumentException("Unable to open $uri")
    }
}

private fun AndroidImageInput.header(): ByteArray =
    if (this is AndroidImageInput.Bytes) bytes else openStream().use { it.readNBytesCompat(HEADER_BYTES) }

private fun AndroidImageInput.allBytes(): ByteArray =
    if (this is AndroidImageInput.Bytes) bytes else openStream().use { it.readBytes() }

private fun AndroidImageInput.exif(): ExifInterface = when (this) {
    is AndroidImageInput.Path -> ExifInterface(file)
    else -> openStream().use { ExifInterface(it) }
}

// InputStream.readNBytes is API 33.
private fun InputStream.readNBytesCompat(count: Int): ByteArray {
    val buffer = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = read(buffer, read, count - read)
        if (n < 0) break
        read += n
    }
    return if (read == count) buffer else buffer.copyOf(read)
}

private const val HEADER_BYTES = 16

/**
 * ImageDecoder applies EXIF rotation and HEIF `irot` itself, and reads HEIC without a JPEG bridge.
 * BitmapFactory stays as the fallback for whatever it refuses, and is the only thing that reads a
 * file whole.
 */
private class AndroidImageSource(private val input: AndroidImageInput) : ImageSource {
    override val displayedSize: ImageSize by lazy { readWithDecoder() ?: readWithBitmapFactory() }

    private fun readWithDecoder(): ImageSize? {
        var size: ImageSize? = null
        try {
            ImageDecoder.decodeBitmap(input.decoderSource()) { _, info, _ ->
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
        input.openStream().use { BitmapFactory.decodeStream(it, null, options) }
        require(options.outWidth > 0 && options.outHeight > 0) { "Failed to read image size" }
        val rotation = input.exif().rotationDegrees
        return if (rotation == 90 || rotation == 270) {
            ImageSize(options.outHeight, options.outWidth)
        } else {
            ImageSize(options.outWidth, options.outHeight)
        }
    }

    override fun decode(minLongEdge: Int): PipelineBitmap {
        // Only a JPEG decoder averages when it samples (DCT scaling). Every other format here picks
        // every Nth pixel, which is the aliasing this pipeline exists to remove.
        val isJpeg = ImageFormatDetector.detectFormat(input.header()) == "image/jpeg"
        val longEdge = max(displayedSize.pixelWidth, displayedSize.pixelHeight)
        val sampleSize = if (isJpeg) sampleSizeFor(longEdge, minLongEdge) else 1
        val guardScale = memoryGuardScale(displayedSize, sampleSize, minLongEdge)
        val bitmap = try {
            decodeGuarded(guardScale, sampleSize)
        } catch (e: OutOfMemoryError) {
            throw IllegalStateException("Not enough memory to decode the image", e)
        }
        return AndroidPipelineBitmap(bitmap)
    }

    private fun decodeGuarded(guardScale: Double?, sampleSize: Int): Bitmap =
        try {
            ImageDecoder.decodeBitmap(input.decoderSource()) { decoder, _, _ ->
                if (guardScale != null) {
                    decoder.setTargetSize(
                        (displayedSize.pixelWidth * guardScale).toInt().coerceAtLeast(1),
                        (displayedSize.pixelHeight * guardScale).toInt().coerceAtLeast(1),
                    )
                } else {
                    decoder.setTargetSampleSize(sampleSize)
                }
                // A HARDWARE bitmap has no readable pixels, so compress would fail on it.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "ImageDecoder refused the image; trying BitmapFactory" }
            ImageUtils.decodeBitmap(input.allBytes(), sampleSize)
        }

    override fun metadata(): ImageMetadata? = try {
        input.exif().toImageMetadata()
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "Android EXIF read failed" }
        null
    }

    override fun close() = Unit
}

/** The biggest power of two that still leaves at least [minLongEdge]; the pipeline scales the rest. */
private fun sampleSizeFor(longEdge: Int, minLongEdge: Int): Int {
    var sample = 1
    while (longEdge / (sample * 2) >= minLongEdge) sample *= 2
    return sample
}

internal actual fun openImageSource(bytes: ByteArray): ImageSource = AndroidImageSource(AndroidImageInput.Bytes(bytes))

internal actual fun openImageFile(path: String): ImageSource? {
    val input = if (path.startsWith("content:")) {
        val context = runCatching { ActivityProvider.requireApplicationContext() }.getOrNull() ?: return null
        AndroidImageInput.Content(context.contentResolver, path.toUri())
    } else {
        AndroidImageInput.Path(File(path.removePrefix("file://")).takeIf { it.isFile } ?: return null)
    }
    return AndroidImageSource(input)
}
