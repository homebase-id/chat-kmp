package id.homebase.api.image

import androidx.compose.ui.graphics.ImageBitmap


import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.asImageBitmap
import co.touchlab.kermit.Logger
import java.io.ByteArrayOutputStream
import androidx.core.graphics.scale

/**
 * Android implementation: Convert ByteArray to ImageBitmap using Android's BitmapFactory
 *
 * Note: Hardware bitmaps (HARDWARE config) cannot be used with Compose ImageBitmap.
 * We configure BitmapFactory to use ARGB_8888 instead to ensure compatibility.
 *
 * Image format detection and validation should be done before calling this function
 * using ImageFormatDetector in common code.
 */
actual fun ByteArray.toImageBitmap(): ImageBitmap? {
    Logger.d("toImageBitmap") { "Android: Converting ${this.size} bytes to ImageBitmap" }

    return try {
        // Configure BitmapFactory to avoid hardware bitmaps
        val options = BitmapFactory.Options().apply {
            // Prevent hardware bitmap allocation (not compatible with Compose)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }

        val bitmap = BitmapFactory.decodeByteArray(this, 0, this.size, options)
        if (bitmap == null) {
            Logger.e("toImageBitmap") { "Android: BitmapFactory.decodeByteArray returned null" }
            Logger.e("toImageBitmap") {
                "First 16 bytes: ${this.take(16).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }}"
            }
            return null
        }

        Logger.d("toImageBitmap") { "Android: Successfully decoded ${bitmap.width}x${bitmap.height}, config=${bitmap.config}" }
        bitmap.asImageBitmap()
    } catch (e: Exception) {
        Logger.e("toImageBitmap", e) { "Android: Decoding failed - ${e.message}" }
        null
    }
}

/**
 * Android implementation of ImageUtils using Android Bitmap APIs
 */
actual object ImageUtils {

    private fun decodeBitmap(bytes: ByteArray): Bitmap {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IllegalArgumentException("Failed to decode image bytes")
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun encodeBitmap(bitmap: Bitmap, format: ImageFormat, quality: Int): ByteArray {
        val compressFormat = when (format) {
            ImageFormat.JPEG -> Bitmap.CompressFormat.JPEG
            ImageFormat.PNG -> Bitmap.CompressFormat.PNG
            ImageFormat.WEBP -> Bitmap.CompressFormat.WEBP_LOSSY
            ImageFormat.BMP -> Bitmap.CompressFormat.PNG // BMP not supported, fallback to PNG
            ImageFormat.GIF -> Bitmap.CompressFormat.WEBP_LOSSY // GIF not supported, fallback to WEBP
        }

        val stream = ByteArrayOutputStream()
        bitmap.compress(compressFormat, quality, stream)
        return stream.toByteArray()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    actual fun resizePreserveAspect(
        srcBytes: ByteArray,
        maxWidth: Int,
        maxHeight: Int,
        outputFormat: ImageFormat,
        quality: Int
    ): ImageResult {
        val srcBitmap = decodeBitmap(srcBytes)
        val naturalW = srcBitmap.width
        val naturalH = srcBitmap.height

        val (targetW, targetH) = calculateTargetDimensions(naturalW, naturalH, maxWidth, maxHeight)

        // If no resize needed
        if (targetW == naturalW && targetH == naturalH) {
            val encoded = encodeBitmap(srcBitmap, outputFormat, quality)
            srcBitmap.recycle()
            return ImageResult(
                bytes = encoded,
                naturalSize = ImageSize(naturalW, naturalH),
                size = ImageSize(naturalW, naturalH)
            )
        }

        // Resize the bitmap
        val resized = srcBitmap.scale(targetW, targetH)
        val encoded = encodeBitmap(resized, outputFormat, quality)

        srcBitmap.recycle()
        if (resized != srcBitmap) resized.recycle()

        return ImageResult(
            bytes = encoded,
            naturalSize = ImageSize(naturalW, naturalH),
            size = ImageSize(targetW, targetH)
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    actual fun compressOnly(
        srcBytes: ByteArray,
        outputFormat: ImageFormat,
        quality: Int
    ): ImageResult {
        val srcBitmap = decodeBitmap(srcBytes)
        val encoded = encodeBitmap(srcBitmap, outputFormat, quality)

        val result = ImageResult(
            bytes = encoded,
            naturalSize = ImageSize(srcBitmap.width, srcBitmap.height),
            size = ImageSize(srcBitmap.width, srcBitmap.height)
        )

        srcBitmap.recycle()
        return result
    }

    @RequiresApi(Build.VERSION_CODES.R)
    actual fun crop(
        srcBytes: ByteArray,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        outputFormat: ImageFormat,
        quality: Int
    ): ImageResult {
        val srcBitmap = decodeBitmap(srcBytes)
        val naturalW = srcBitmap.width
        val naturalH = srcBitmap.height

        val sx = x.coerceAtLeast(0)
        val sy = y.coerceAtLeast(0)
        val sw = width.coerceAtMost(naturalW - sx).coerceAtLeast(1)
        val sh = height.coerceAtMost(naturalH - sy).coerceAtLeast(1)

        val cropped = Bitmap.createBitmap(srcBitmap, sx, sy, sw, sh)
        val encoded = encodeBitmap(cropped, outputFormat, quality)

        srcBitmap.recycle()
        if (cropped != srcBitmap) cropped.recycle()

        return ImageResult(
            bytes = encoded,
            naturalSize = ImageSize(naturalW, naturalH),
            size = ImageSize(sw, sh)
        )
    }

    @RequiresApi(Build.VERSION_CODES.R)
    actual fun rotate(
        srcBytes: ByteArray,
        degrees: Int,
        outputFormat: ImageFormat,
        quality: Int
    ): ImageResult {
        val srcBitmap = decodeBitmap(srcBytes)
        val naturalW = srcBitmap.width
        val naturalH = srcBitmap.height

        val matrix = Matrix().apply {
            postRotate(degrees.toFloat())
        }

        val rotated = Bitmap.createBitmap(srcBitmap, 0, 0, naturalW, naturalH, matrix, true)
        val encoded = encodeBitmap(rotated, outputFormat, quality)

        srcBitmap.recycle()
        if (rotated != srcBitmap) rotated.recycle()

        return ImageResult(
            bytes = encoded,
            naturalSize = ImageSize(naturalW, naturalH),
            size = ImageSize(rotated.width, rotated.height)
        )
    }

    actual fun getNaturalSize(srcBytes: ByteArray): ImageSize {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(srcBytes, 0, srcBytes.size, options)
        return ImageSize(options.outWidth, options.outHeight)
    }
}

