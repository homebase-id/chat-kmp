package id.homebase.api.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import co.touchlab.kermit.Logger
import id.homebase.api.image.draw.PathCommand
import id.homebase.api.image.draw.StrokeCap
import id.homebase.api.image.draw.StrokeCommand
import id.homebase.api.lib.image.ImageFormatDetector
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.IRect
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.Path
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

/**
 * Desktop/JVM: Convert HEIC to JPEG using the bundled FFmpeg binary.
 */
actual fun convertHeicToJpeg(heicBytes: ByteArray): ByteArray? {
    return try {
        if (!id.homebase.api.video.FFmpegBinaryManager.isAvailable()) {
            Logger.w(tag = "convertHeicToJpeg") { "FFmpeg not available, cannot convert HEIC" }
            return null
        }
        val tmpDir = System.getProperty("java.io.tmpdir")
        val inputFile = java.io.File(tmpDir, "heic_input_${System.nanoTime()}.heic")
        val outputFile = java.io.File(tmpDir, "heic_output_${System.nanoTime()}.jpg")
        try {
            inputFile.writeBytes(heicBytes)
            val command = listOf(
                id.homebase.api.video.FFmpegBinaryManager.ffmpegPath(),
                "-y", "-i", inputFile.absolutePath,
                "-q:v", "2",
                outputFile.absolutePath
            )
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }
            val completed = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
            if (completed && process.exitValue() == 0 && outputFile.exists()) {
                outputFile.readBytes()
            } else {
                Logger.w(tag = "convertHeicToJpeg") { "FFmpeg HEIC conversion failed (exit=${if (completed) process.exitValue() else -1})" }
                null
            }
        } finally {
            inputFile.delete()
            outputFile.delete()
        }
    } catch (e: Exception) {
        Logger.e(throwable = e, tag = "convertHeicToJpeg") { "Desktop HEIC conversion failed" }
        null
    }
}

/**
 * Desktop/JVM implementation: Convert ByteArray to ImageBitmap using Skia
 */
actual fun ByteArray.toImageBitmap(): ImageBitmap? {
    Logger.d(tag = "toImageBitmap") { "toImageBitmap starting" }
    return try {
        Image.makeFromEncoded(this).toComposeImageBitmap()
    } catch (e: Exception) {
        Logger.e(throwable = e, tag = "toImageBitmap") { "toImageBitmap failed: ${e.message}" }
        null
    }
}

/**
 * Desktop implementation of ImageUtils using Skia
 */
actual object ImageUtils {

    private fun decodeImage(bytes: ByteArray): Image {
        val inputBytes = if (ImageFormatDetector.isHeic(bytes)) {
            convertHeicToJpeg(bytes) ?: throw IllegalArgumentException("Failed to convert HEIC to JPEG")
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

    actual fun warpAffine(
        srcBytes: ByteArray,
        matrix9: FloatArray,
        outputWidth: Int,
        outputHeight: Int,
        fillColorArgb: Int,
        outputFormat: ImageFormat,
        quality: Int,
    ): ImageResult {
        require(matrix9.size >= 9) { "matrix9 must have at least 9 entries" }
        require(outputWidth > 0 && outputHeight > 0) { "output dimensions must be positive" }

        val srcImage = decodeImage(srcBytes)
        val naturalW = srcImage.width
        val naturalH = srcImage.height

        val surface = Surface.makeRasterN32Premul(outputWidth, outputHeight)
        val canvas = surface.canvas
        if (fillColorArgb != 0) {
            val fillPaint = org.jetbrains.skia.Paint().apply {
                color = fillColorArgb
            }
            canvas.drawRect(Rect.makeWH(outputWidth.toFloat(), outputHeight.toFloat()), fillPaint)
        }
        // Skia matrix from row-major Android-style 9-float array.
        val skiaMatrix = org.jetbrains.skia.Matrix33(
            matrix9[0], matrix9[1], matrix9[2],
            matrix9[3], matrix9[4], matrix9[5],
            matrix9[6], matrix9[7], matrix9[8],
        )
        canvas.save()
        canvas.concat(skiaMatrix)
        val drawPaint = org.jetbrains.skia.Paint().apply {
            isAntiAlias = true
        }
        canvas.drawImage(srcImage, 0f, 0f, drawPaint)
        canvas.restore()

        val warped = surface.makeImageSnapshot()
        val encoded = warped.encodeToData(encodedFormatFor(outputFormat), quality)
            ?: throw IllegalStateException("Failed to encode warped image")
        return ImageResult(
            bytes = encoded.bytes,
            naturalSize = ImageSize(naturalW, naturalH),
            size = ImageSize(outputWidth, outputHeight),
        )
    }

    actual fun drawStrokes(
        srcBytes: ByteArray,
        strokes: List<StrokeCommand>,
        outputFormat: ImageFormat,
        quality: Int,
    ): ImageResult {
        val srcImage = decodeImage(srcBytes)
        val w = srcImage.width
        val h = srcImage.height

        val surface = Surface.makeRasterN32Premul(w, h)
        val canvas = surface.canvas
        canvas.drawImage(srcImage, 0f, 0f)

        for (cmd in strokes) {
            val paint = Paint().apply {
                isAntiAlias = true
                mode = PaintMode.STROKE
                strokeWidth = cmd.thicknessPx
                strokeCap = when (cmd.cap) {
                    StrokeCap.Round -> PaintStrokeCap.ROUND
                    StrokeCap.Square -> PaintStrokeCap.SQUARE
                }
                color = cmd.colorArgb
            }
            val path = Path()
            for (pc in cmd.pathCommands) when (pc) {
                is PathCommand.MoveTo -> path.moveTo(pc.x, pc.y)
                is PathCommand.LineTo -> path.lineTo(pc.x, pc.y)
                is PathCommand.CubicTo -> path.cubicTo(pc.c1x, pc.c1y, pc.c2x, pc.c2y, pc.x, pc.y)
            }
            canvas.drawPath(path, paint)
            paint.close()
            path.close()
        }

        val out = surface.makeImageSnapshot()
        val encoded = out.encodeToData(encodedFormatFor(outputFormat), quality)
            ?: throw IllegalStateException("Failed to encode painted image")
        return ImageResult(
            bytes = encoded.bytes,
            naturalSize = ImageSize(w, h),
            size = ImageSize(w, h),
        )
    }
}

