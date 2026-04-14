package id.homebase.api.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import id.homebase.api.lib.image.ImageFormatDetector
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.IRect
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import platform.Foundation.dataWithBytes

/**
 * iOS: Convert HEIC to JPEG using native UIImage APIs.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun convertHeicToJpeg(heicBytes: ByteArray): ByteArray? {
    return try {
        val nsData = heicBytes.usePinned { pinned ->
            platform.Foundation.NSData.dataWithBytes(pinned.addressOf(0), heicBytes.size.toULong())
        }
        val uiImage = platform.UIKit.UIImage.imageWithData(nsData) ?: return null
        val jpegData = platform.UIKit.UIImageJPEGRepresentation(uiImage, 0.95) ?: return null
        ByteArray(jpegData.length.toInt()).also { bytes ->
            bytes.usePinned { pinned ->
                platform.posix.memcpy(pinned.addressOf(0), jpegData.bytes, jpegData.length)
            }
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * iOS implementation: Convert ByteArray to ImageBitmap using Skia
 */
actual fun ByteArray.toImageBitmap(): ImageBitmap? {
    return try {
        Image.makeFromEncoded(this).toComposeImageBitmap()
    } catch (e: Exception) {
        null
    }
}

/**
 * iOS implementation of ImageUtils using Skia
 */
actual object ImageUtils {

    private fun decodeImage(bytes: ByteArray): Image {
        val inputBytes = if (ImageFormatDetector.isHeic(bytes)) {
            convertHeicToJpeg(bytes)
                ?: throw IllegalArgumentException("Failed to convert HEIC to JPEG")
        } else bytes
        // Image.makeFromEncoded applies EXIF orientation automatically (dims + pixels)
        return Image.makeFromEncoded(inputBytes)
    }

    private fun encodedFormatFor(format: ImageFormat): EncodedImageFormat = when (format) {
        ImageFormat.WEBP -> EncodedImageFormat.WEBP
        ImageFormat.JPEG -> EncodedImageFormat.JPEG
        ImageFormat.PNG -> EncodedImageFormat.PNG
        ImageFormat.BMP -> EncodedImageFormat.PNG // BMP encoding not widely supported, fallback to PNG
        ImageFormat.GIF -> EncodedImageFormat.WEBP // GIF encoding not supported, fallback to WEBP
    }

    actual fun resizePreserveAspect(
        srcBytes: ByteArray,
        maxWidth: Int,
        maxHeight: Int,
        outputFormat: ImageFormat,
        quality: Int
    ): ImageResult {
        val srcImage = decodeImage(srcBytes)
        val naturalW = srcImage.width
        val naturalH = srcImage.height

        val (targetW, targetH) = calculateTargetDimensions(naturalW, naturalH, maxWidth, maxHeight)

        // If no resize needed
        if (targetW == naturalW && targetH == naturalH) {
            val format = encodedFormatFor(outputFormat)
            val data = srcImage.encodeToData(format, quality)
            return ImageResult(
                bytes = data?.bytes ?: srcBytes,
                naturalSize = ImageSize(naturalW, naturalH),
                size = ImageSize(naturalW, naturalH)
            )
        }

        // Create surface for resized image
        val surface = Surface.makeRasterN32Premul(targetW, targetH)
        val canvas = surface.canvas

        // Scale and draw
        canvas.scale(targetW.toFloat() / naturalW, targetH.toFloat() / naturalH)
        canvas.drawImage(srcImage, 0f, 0f)

        // Get the resized image
        val resized = surface.makeImageSnapshot()
        val encoded = resized.encodeToData(encodedFormatFor(outputFormat), quality)
            ?: throw IllegalStateException("Failed to encode resized image")

        return ImageResult(
            bytes = encoded.bytes,
            naturalSize = ImageSize(naturalW, naturalH),
            size = ImageSize(targetW, targetH)
        )
    }

    actual fun compressOnly(
        srcBytes: ByteArray,
        outputFormat: ImageFormat,
        quality: Int
    ): ImageResult {
        val srcImage = decodeImage(srcBytes)
        val encoded = srcImage.encodeToData(encodedFormatFor(outputFormat), quality)
            ?: throw IllegalStateException("Failed to encode image")

        return ImageResult(
            bytes = encoded.bytes,
            naturalSize = ImageSize(srcImage.width, srcImage.height),
            size = ImageSize(srcImage.width, srcImage.height)
        )
    }

    actual fun crop(
        srcBytes: ByteArray,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        outputFormat: ImageFormat,
        quality: Int
    ): ImageResult {
        val srcImage = decodeImage(srcBytes)
        val naturalW = srcImage.width
        val naturalH = srcImage.height

        val sx = x.coerceAtLeast(0)
        val sy = y.coerceAtLeast(0)
        val sw = width.coerceAtMost(naturalW - sx).coerceAtLeast(1)
        val sh = height.coerceAtMost(naturalH - sy).coerceAtLeast(1)

        // Create a new surface for the cropped region
        val surface = Surface.makeRasterN32Premul(sw, sh)
        val canvas = surface.canvas

        // Draw the cropped portion
        canvas.drawImageRect(
            srcImage,
            IRect.makeXYWH(sx, sy, sw, sh).toRect(),
            Rect.makeWH(sw.toFloat(), sh.toFloat())
        )

        val cropped = surface.makeImageSnapshot()
        val encoded = cropped.encodeToData(encodedFormatFor(outputFormat), quality)
            ?: throw IllegalStateException("Failed to encode cropped image")

        return ImageResult(
            bytes = encoded.bytes,
            naturalSize = ImageSize(naturalW, naturalH),
            size = ImageSize(sw, sh)
        )
    }

    actual fun rotate(
        srcBytes: ByteArray,
        degrees: Int,
        outputFormat: ImageFormat,
        quality: Int
    ): ImageResult {
        val srcImage = decodeImage(srcBytes)
        val naturalW = srcImage.width
        val naturalH = srcImage.height

        // Normalize degrees to 0-359
        val normalizedDegrees = ((degrees % 360) + 360) % 360

        // Calculate new dimensions after rotation
        val (newW, newH) = when (normalizedDegrees) {
            90, 270 -> naturalH to naturalW
            else -> naturalW to naturalH
        }

        // Create surface for rotated image
        val surface = Surface.makeRasterN32Premul(newW, newH)
        val canvas = surface.canvas

        // Apply rotation transformation
        when (normalizedDegrees) {
            0 -> {
                canvas.drawImage(srcImage, 0f, 0f)
            }

            90 -> {
                canvas.translate(newW.toFloat(), 0f)
                canvas.rotate(90f)
                canvas.drawImage(srcImage, 0f, 0f)
            }

            180 -> {
                canvas.translate(newW.toFloat(), newH.toFloat())
                canvas.rotate(180f)
                canvas.drawImage(srcImage, 0f, 0f)
            }

            270 -> {
                canvas.translate(0f, newH.toFloat())
                canvas.rotate(270f)
                canvas.drawImage(srcImage, 0f, 0f)
            }

            else -> {
                // For arbitrary angles, rotate around center
                val centerX = newW / 2f
                val centerY = newH / 2f
                canvas.translate(centerX, centerY)
                canvas.rotate(normalizedDegrees.toFloat())
                canvas.translate(-naturalW / 2f, -naturalH / 2f)
                canvas.drawImage(srcImage, 0f, 0f)
            }
        }

        val rotated = surface.makeImageSnapshot()
        val encoded = rotated.encodeToData(encodedFormatFor(outputFormat), quality)
            ?: throw IllegalStateException("Failed to encode rotated image")

        return ImageResult(
            bytes = encoded.bytes,
            naturalSize = ImageSize(naturalW, naturalH),
            size = ImageSize(rotated.width, rotated.height)
        )
    }

    actual fun getNaturalSize(srcBytes: ByteArray): ImageSize {
        val img = decodeImage(srcBytes)
        return ImageSize(img.width, img.height)
    }
}
