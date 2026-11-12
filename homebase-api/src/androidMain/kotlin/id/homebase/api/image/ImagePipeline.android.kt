package id.homebase.api.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import co.touchlab.kermit.Logger
import id.homebase.api.file.AndroidFileOperationsProvider
import id.homebase.api.file.FileOperationsProvider
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
        val colorSpace = eightBit.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888, eightBit.hasAlpha(), colorSpace)
        try {
            NativeResize.lanczos(eightBit, out)
            AndroidPipelineBitmap(out)
        } catch (e: Throwable) {
            out.recycle()
            throw e
        } finally {
            if (eightBit !== bitmap) eightBit.recycle()
        }
    } catch (e: OutOfMemoryError) {
        throw IllegalStateException("Not enough memory to scale the image", e)
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

private sealed interface AndroidImageInput {
    fun decoderSource(): ImageDecoder.Source
    fun openStream(): InputStream
    fun allBytes(): ByteArray = openStream().use { it.readBytes() }
    fun exif(): ExifInterface = openStream().use { ExifInterface(it) }

    class Bytes(val bytes: ByteArray) : AndroidImageInput {
        override fun decoderSource(): ImageDecoder.Source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        override fun openStream(): InputStream = ByteArrayInputStream(bytes)
        override fun allBytes() = bytes
    }

    class Path(val file: File) : AndroidImageInput {
        override fun decoderSource(): ImageDecoder.Source = ImageDecoder.createSource(file)
        override fun openStream(): InputStream = file.inputStream()
        override fun exif() = ExifInterface(file)
    }

    class Content(val files: AndroidFileOperationsProvider, val path: String) : AndroidImageInput {
        override fun decoderSource(): ImageDecoder.Source = ImageDecoder.createSource(files.context.contentResolver, path.toUri())
        override fun openStream(): InputStream = files.openContentStream(path)
    }
}

private class Header(val size: ImageSize, val mimeType: String?)

/**
 * ImageDecoder applies EXIF rotation and HEIF `irot` itself, and reads HEIC without a JPEG bridge.
 * BitmapFactory stays as the fallback for whatever it refuses.
 */
private class AndroidImageSource(private val input: AndroidImageInput) : ImageSource {
    private val header: Header by lazy { readWithDecoder() ?: readWithBitmapFactory() }
    private val exif: ExifInterface by lazy { input.exif() }

    override val displayedSize get() = header.size

    private fun readWithDecoder(): Header? {
        var header: Header? = null
        try {
            ImageDecoder.decodeBitmap(input.decoderSource()) { _, info, _ ->
                header = Header(ImageSize(info.size.width, info.size.height), info.mimeType)
                throw HeaderRead() // Stops before any pixels are allocated.
            }
        } catch (_: HeaderRead) {
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "ImageDecoder couldn't read the header" }
        }
        return header
    }

    private fun readWithBitmapFactory(): Header {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        input.openStream().use { BitmapFactory.decodeStream(it, null, options) }
        require(options.outWidth > 0 && options.outHeight > 0) { "Failed to read image size" }
        val rotation = exif.rotationDegrees
        val size = if (rotation == 90 || rotation == 270) {
            ImageSize(options.outHeight, options.outWidth)
        } else {
            ImageSize(options.outWidth, options.outHeight)
        }
        return Header(size, options.outMimeType)
    }

    override fun decode(minLongEdge: Int): PipelineBitmap {
        // Only a JPEG decoder averages when it samples (DCT scaling). Every other format here picks
        // every Nth pixel, which is the aliasing this pipeline exists to remove.
        val isJpeg = header.mimeType == "image/jpeg"
        val longEdge = max(displayedSize.pixelWidth, displayedSize.pixelHeight)
        val sampleSize = if (isJpeg) sampleSizeFor(longEdge, minLongEdge) else 1
        val bitmap = try {
            decodeSampled(sampleSize)
        } catch (e: OutOfMemoryError) {
            throw IllegalStateException("Not enough memory to decode the image", e)
        }
        return AndroidPipelineBitmap(bitmap)
    }

    // Not setTargetSize: it decodes full size, then scales bilinearly, so it aliases and peaks higher.
    private fun decodeSampled(sampleSize: Int): Bitmap =
        try {
            ImageDecoder.decodeBitmap(input.decoderSource()) { decoder, _, _ ->
                decoder.setTargetSampleSize(sampleSize)
                // A HARDWARE bitmap has no readable pixels, so compress would fail on it.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "ImageDecoder refused the image; trying BitmapFactory" }
            ImageUtils.decodeBitmap(input.allBytes(), sampleSize)
        }

    override fun metadata(): ImageMetadata? = try {
        exif.toImageMetadata()
    } catch (e: Throwable) {
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

internal actual fun openImageFile(path: String, fileOps: FileOperationsProvider): ImageSource? {
    val files = fileOps as? AndroidFileOperationsProvider
    val input = when {
        files != null && files.isContentUri(path) -> AndroidImageInput.Content(files, path)
        else -> AndroidImageInput.Path(File(path).takeIf { it.isFile } ?: return null)
    }
    return AndroidImageSource(input)
}
