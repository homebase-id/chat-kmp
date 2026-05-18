package id.homebase.api.image

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import id.homebase.api.image.draw.PathCommand
import id.homebase.api.image.draw.StrokeCap
import id.homebase.api.image.draw.StrokeCommand
import id.homebase.api.image.draw.StrokeKind
import id.homebase.api.image.draw.stackBlur
import id.homebase.api.lib.image.ImageFormatDetector
import org.jetbrains.skia.Bitmap as SkiaBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.IRect
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.Path
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
            val fillPaint = org.jetbrains.skia.Paint().apply { color = fillColorArgb }
            canvas.drawRect(Rect.makeWH(outputWidth.toFloat(), outputHeight.toFloat()), fillPaint)
        }
        val skiaMatrix = org.jetbrains.skia.Matrix33(
            matrix9[0], matrix9[1], matrix9[2],
            matrix9[3], matrix9[4], matrix9[5],
            matrix9[6], matrix9[7], matrix9[8],
        )
        canvas.save()
        canvas.concat(skiaMatrix)
        val drawPaint = org.jetbrains.skia.Paint().apply { isAntiAlias = true }
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

        var blurredImage: org.jetbrains.skia.Image? = null

        for (cmd in strokes) {
            val path = Path()
            for (pc in cmd.pathCommands) when (pc) {
                is PathCommand.MoveTo -> path.moveTo(pc.x, pc.y)
                is PathCommand.LineTo -> path.lineTo(pc.x, pc.y)
                is PathCommand.CubicTo -> path.cubicTo(pc.c1x, pc.c1y, pc.c2x, pc.c2y, pc.x, pc.y)
            }

            when (cmd.kind) {
                StrokeKind.PAINT -> {
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
                    canvas.drawPath(path, paint)
                    paint.close()
                }
                StrokeKind.BLUR -> {
                    val blurred = blurredImage ?: blurSkiaImage(srcImage, BLUR_RADIUS).also {
                        blurredImage = it
                    }
                    val blurPaint = Paint().apply {
                        isAntiAlias = true
                        mode = PaintMode.STROKE
                        strokeWidth = cmd.thicknessPx
                        strokeCap = when (cmd.cap) {
                            StrokeCap.Round -> PaintStrokeCap.ROUND
                            StrokeCap.Square -> PaintStrokeCap.SQUARE
                        }
                        shader = blurred.makeShader()
                    }
                    canvas.drawPath(path, blurPaint)
                    blurPaint.close()
                }
            }
            path.close()
        }

        val out = surface.makeImageSnapshot()
        val encoded = out.encodeToData(encodedFormatFor(outputFormat), quality)
            ?: throw IllegalStateException("Failed to encode painted image")
        blurredImage?.close()
        return ImageResult(
            bytes = encoded.bytes,
            naturalSize = ImageSize(w, h),
            size = ImageSize(w, h),
        )
    }

    actual fun blurBytes(
        srcBytes: ByteArray,
        radius: Int,
        outputFormat: ImageFormat,
        quality: Int,
    ): ImageResult {
        val srcImage = decodeImage(srcBytes)
        val blurred = blurSkiaImage(srcImage, radius)
        val encoded = blurred.encodeToData(encodedFormatFor(outputFormat), quality)
            ?: throw IllegalStateException("Failed to encode blurred image")
        val w = srcImage.width; val h = srcImage.height
        blurred.close()
        return ImageResult(
            bytes = encoded.bytes,
            naturalSize = ImageSize(w, h),
            size = ImageSize(w, h),
        )
    }

    private fun blurSkiaImage(src: org.jetbrains.skia.Image, radius: Int): org.jetbrains.skia.Image {
        val w = src.width; val h = src.height
        val info = ImageInfo(
            colorInfo = ColorInfo(ColorType.BGRA_8888, ColorAlphaType.UNPREMUL, ColorSpace.sRGB),
            width = w,
            height = h,
        )
        val rowBytes = w * 4
        val readBitmap = SkiaBitmap()
        check(readBitmap.allocPixels(info)) { "Skia bitmap alloc failed" }
        check(src.readPixels(readBitmap)) { "Skia readPixels failed" }
        val bytes = readBitmap.readPixels(info, rowBytes, 0, 0)
            ?: throw IllegalStateException("Skia bitmap readPixels returned null")
        readBitmap.close()
        val pixels = IntArray(w * h)
        for (i in 0 until w * h) {
            val b0 = bytes[i * 4].toInt() and 0xFF
            val g0 = bytes[i * 4 + 1].toInt() and 0xFF
            val r0 = bytes[i * 4 + 2].toInt() and 0xFF
            val a0 = bytes[i * 4 + 3].toInt() and 0xFF
            pixels[i] = (a0 shl 24) or (r0 shl 16) or (g0 shl 8) or b0
        }
        stackBlur(pixels, w, h, radius)
        for (i in 0 until w * h) {
            val px = pixels[i]
            bytes[i * 4]     = (px and 0xFF).toByte()
            bytes[i * 4 + 1] = ((px shr 8) and 0xFF).toByte()
            bytes[i * 4 + 2] = ((px shr 16) and 0xFF).toByte()
            bytes[i * 4 + 3] = ((px ushr 24) and 0xFF).toByte()
        }
        return org.jetbrains.skia.Image.makeRaster(info, bytes, rowBytes)
    }

    private const val BLUR_RADIUS: Int = 25

    actual suspend fun rasterizeSvg(
        svgBytes: ByteArray,
        maxDim: Int,
        outputFormat: ImageFormat,
        quality: Int,
    ): ImageResult {
        val dom = org.jetbrains.skia.svg.SVGDOM(org.jetbrains.skia.Data.makeFromBytes(svgBytes))

        val root = dom.root ?: throw IllegalArgumentException("SVG has no root element")
        val intrinsicW = root.width.value.toInt().takeIf { it > 0 }
        val intrinsicH = root.height.value.toInt().takeIf { it > 0 }
        val (naturalW, naturalH) = when {
            intrinsicW != null && intrinsicH != null -> intrinsicW to intrinsicH
            else -> parseSvgDimensions(svgBytes) ?: (320 to 320)
        }
        dom.setContainerSize(naturalW.toFloat(), naturalH.toFloat())

        // Vector → always render at exactly the requested maxDim box,
        // preserving aspect. Same rationale as the JVM actual.
        val scale = maxDim.toFloat() / maxOf(naturalW, naturalH)
        val targetW = (naturalW * scale).toInt().coerceAtLeast(1)
        val targetH = (naturalH * scale).toInt().coerceAtLeast(1)

        val surface = Surface.makeRasterN32Premul(targetW, targetH)
        val canvas = surface.canvas
        canvas.scale(targetW.toFloat() / naturalW, targetH.toFloat() / naturalH)
        dom.render(canvas)

        val rendered = surface.makeImageSnapshot()
        val encoded = rendered.encodeToData(encodedFormatFor(outputFormat), quality)
            ?: throw IllegalStateException("Failed to encode rasterized SVG to ${outputFormat.name}")

        return ImageResult(
            bytes = encoded.bytes,
            naturalSize = ImageSize(naturalW, naturalH),
            size = ImageSize(targetW, targetH)
        )
    }
}

// Last-resort dimension parser when SVGDOM doesn't expose intrinsic
// width/height (viewBox-only SVGs commonly do this). Same shape as
// ThumbnailGenerator.getSvgDimensions — duplicated here to keep
// ImageUtils per-platform actuals self-contained.
private fun parseSvgDimensions(svgBytes: ByteArray): Pair<Int, Int>? {
    val text = svgBytes.decodeToString()
    val w = Regex("""width\s*=\s*["']?(\d+)(?:px)?""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    val h = Regex("""height\s*=\s*["']?(\d+)(?:px)?""").find(text)?.groupValues?.get(1)?.toIntOrNull()
    return if (w != null && h != null) w to h else null
}
