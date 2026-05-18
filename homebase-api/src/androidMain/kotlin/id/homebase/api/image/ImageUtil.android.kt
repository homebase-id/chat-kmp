package id.homebase.api.image


import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.scale
import co.touchlab.kermit.Logger
import id.homebase.api.lib.image.ImageFormatDetector
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import androidx.exifinterface.media.ExifInterface
import id.homebase.api.image.draw.PathCommand
import id.homebase.api.image.draw.StrokeCap
import id.homebase.api.image.draw.StrokeCommand
import id.homebase.api.image.draw.StrokeKind
import id.homebase.api.image.draw.stackBlur
import android.graphics.BitmapShader
import android.graphics.Shader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.RectF
import com.caverock.androidsvg.SVG

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
    Logger.d(tag = "toImageBitmap") { "Android: Converting ${size} bytes to ImageBitmap" }

    return try {
        // Configure BitmapFactory to avoid hardware bitmaps
        val options = BitmapFactory.Options().apply {
            // Prevent hardware bitmap allocation (not compatible with Compose)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = false
        }

        val bitmap = BitmapFactory.decodeByteArray(this, 0, this.size, options)
        if (bitmap == null) {
            Logger.e(tag = "toImageBitmap") { "Android: BitmapFactory.decodeByteArray returned null" }
            Logger.e(tag = "toImageBitmap") {
                "First 16 bytes: ${
                    take(16).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                }"
            }
            return null
        }

        Logger.d(tag = "toImageBitmap") { "Android: Successfully decoded ${bitmap.width}x${bitmap.height}, config=${bitmap.config}" }
        bitmap.asImageBitmap()
    } catch (e: Exception) {
        Logger.e(throwable = e, tag = "toImageBitmap") { "Android: Decoding failed - ${e.message}" }
        null
    }
}

/**
 * Android: Convert HEIC to JPEG using BitmapFactory (supports HEIC on API 28+).
 */
actual fun convertHeicToJpeg(heicBytes: ByteArray): ByteArray? {
    return try {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size, options)
            ?: return null
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)
        bitmap.recycle()
        stream.toByteArray()
    } catch (e: Exception) {
        Logger.e(throwable = e, tag = "convertHeicToJpeg") { "Android HEIC conversion failed" }
        null
    }
}

/**
 * Android implementation of ImageUtils using Android Bitmap APIs
 */
actual object ImageUtils {

    private fun decodeBitmap(bytes: ByteArray): Bitmap {
        val inputBytes = if (ImageFormatDetector.isHeic(bytes)) {
            convertHeicToJpeg(bytes) ?: throw IllegalArgumentException("Failed to convert HEIC to JPEG")
        } else bytes
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val bitmap = BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size, options)
            ?: throw IllegalArgumentException("Failed to decode image bytes")
        return applyExifOrientation(bitmap, inputBytes)
    }

    private fun applyExifOrientation(bitmap: Bitmap, imageBytes: ByteArray): Bitmap {
        val exif = ExifInterface(ByteArrayInputStream(imageBytes))
        val orientation = exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
        )
        if (orientation == ExifInterface.ORIENTATION_NORMAL ||
            orientation == ExifInterface.ORIENTATION_UNDEFINED
        ) {
            return bitmap
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }

        val corrected = Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
        )
        if (corrected != bitmap) bitmap.recycle()
        return corrected
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
        val inputBytes = if (ImageFormatDetector.isHeic(srcBytes)) {
            convertHeicToJpeg(srcBytes) ?: throw IllegalArgumentException("Failed to convert HEIC to JPEG for size detection")
        } else srcBytes
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(inputBytes, 0, inputBytes.size, options)
        return ImageSize(options.outWidth, options.outHeight)
    }

    @RequiresApi(Build.VERSION_CODES.R)
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

        val srcBitmap = decodeBitmap(srcBytes)
        val naturalW = srcBitmap.width
        val naturalH = srcBitmap.height

        val matrix = Matrix().apply { setValues(matrix9) }
        val out = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        if (fillColorArgb != 0) {
            canvas.drawColor(fillColorArgb)
        }
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }
        canvas.drawBitmap(srcBitmap, matrix, paint)

        val encoded = encodeBitmap(out, outputFormat, quality)
        srcBitmap.recycle()
        out.recycle()
        return ImageResult(
            bytes = encoded,
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
        val srcBitmap = decodeBitmap(srcBytes)
        val w = srcBitmap.width
        val h = srcBitmap.height

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(out)
        canvas.drawBitmap(srcBitmap, 0f, 0f, null)

        var blurredBitmap: Bitmap? = null

        for (cmd in strokes) {
            val path = android.graphics.Path()
            for (pc in cmd.pathCommands) when (pc) {
                is PathCommand.MoveTo -> path.moveTo(pc.x, pc.y)
                is PathCommand.LineTo -> path.lineTo(pc.x, pc.y)
                is PathCommand.CubicTo -> path.cubicTo(pc.c1x, pc.c1y, pc.c2x, pc.c2y, pc.x, pc.y)
            }

            when (cmd.kind) {
                StrokeKind.PAINT -> {
                    val paint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        style = android.graphics.Paint.Style.STROKE
                        strokeJoin = android.graphics.Paint.Join.ROUND
                        strokeWidth = cmd.thicknessPx
                        strokeCap = when (cmd.cap) {
                            StrokeCap.Round -> android.graphics.Paint.Cap.ROUND
                            StrokeCap.Square -> android.graphics.Paint.Cap.SQUARE
                        }
                        color = cmd.colorArgb
                    }
                    canvas.drawPath(path, paint)
                }
                StrokeKind.BLUR -> {
                    val blurred = blurredBitmap ?: blurAndroidBitmap(srcBitmap, BLUR_RADIUS).also {
                        blurredBitmap = it
                    }
                    val blurPaint = android.graphics.Paint().apply {
                        isAntiAlias = true
                        style = android.graphics.Paint.Style.STROKE
                        strokeJoin = android.graphics.Paint.Join.ROUND
                        strokeWidth = cmd.thicknessPx
                        strokeCap = when (cmd.cap) {
                            StrokeCap.Round -> android.graphics.Paint.Cap.ROUND
                            StrokeCap.Square -> android.graphics.Paint.Cap.SQUARE
                        }
                        shader = BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    }
                    canvas.drawPath(path, blurPaint)
                }
            }
        }

        val encoded = encodeBitmap(out, outputFormat, quality)
        srcBitmap.recycle()
        out.recycle()
        blurredBitmap?.recycle()
        return ImageResult(
            bytes = encoded,
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
        val srcBitmap = decodeBitmap(srcBytes)
        val blurred = blurAndroidBitmap(srcBitmap, radius)
        val encoded = encodeBitmap(blurred, outputFormat, quality)
        val w = srcBitmap.width
        val h = srcBitmap.height
        srcBitmap.recycle()
        blurred.recycle()
        return ImageResult(
            bytes = encoded,
            naturalSize = ImageSize(w, h),
            size = ImageSize(w, h),
        )
    }

    private fun blurAndroidBitmap(src: Bitmap, radius: Int): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        stackBlur(pixels, w, h, radius)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private const val BLUR_RADIUS: Int = 25

    @RequiresApi(Build.VERSION_CODES.R)
    actual suspend fun rasterizeSvg(
        svgBytes: ByteArray,
        maxDim: Int,
        outputFormat: ImageFormat,
        quality: Int,
    ): ImageResult {
        val svg = SVG.getFromInputStream(ByteArrayInputStream(svgBytes))

        // AndroidSVG's documentWidth/Height returns -1 when the SVG only
        // declares a viewBox. Fall back to the viewBox extents, then to
        // a 320×320 box. Same approach as the Skiko actuals.
        val docW = svg.documentWidth.toInt().takeIf { it > 0 }
        val docH = svg.documentHeight.toInt().takeIf { it > 0 }
        val viewBox = svg.documentViewBox
        val (naturalW, naturalH) = when {
            docW != null && docH != null -> docW to docH
            viewBox != null && viewBox.width() > 0 && viewBox.height() > 0 ->
                viewBox.width().toInt() to viewBox.height().toInt()
            else -> 320 to 320
        }

        // Vector → always render at exactly the requested maxDim box,
        // preserving aspect. (calculateTargetDimensions refuses to upscale
        // — correct for rasters, wrong for vectors.)
        val scale = maxDim.toFloat() / maxOf(naturalW, naturalH)
        val targetW = (naturalW * scale).toInt().coerceAtLeast(1)
        val targetH = (naturalH * scale).toInt().coerceAtLeast(1)

        // Tell AndroidSVG the render container so percentage-sized
        // elements scale correctly when the SVG is viewBox-only.
        svg.setDocumentWidth(naturalW.toFloat())
        svg.setDocumentHeight(naturalH.toFloat())

        val bitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        try {
            val canvas = AndroidCanvas(bitmap)
            svg.renderToCanvas(canvas, RectF(0f, 0f, targetW.toFloat(), targetH.toFloat()))
            val bytes = encodeBitmap(bitmap, outputFormat, quality)
            return ImageResult(
                bytes = bytes,
                naturalSize = ImageSize(naturalW, naturalH),
                size = ImageSize(targetW, targetH),
            )
        } finally {
            bitmap.recycle()
        }
    }
}

