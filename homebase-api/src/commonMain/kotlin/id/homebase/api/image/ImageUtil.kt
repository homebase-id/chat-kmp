package id.homebase.api.image

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.roundToInt

/**
 * Convert a ByteArray (containing encoded image data like PNG, JPEG) to an ImageBitmap.
 * Platform-specific implementations handle the decoding.
 */
expect fun ByteArray.toImageBitmap(): ImageBitmap?

/**
 * Platform-specific image manipulation operations.
 * Each platform implements this using their native image libraries.
 */
expect object ImageUtils {
    /**
     * Resize to fit inside (maxWidth x maxHeight) while preserving aspect ratio.
     * If maxWidth or maxHeight are <= 0, it will not scale on that axis.
     */
    fun resizePreserveAspect(
        srcBytes: ByteArray,
        maxWidth: Int,
        maxHeight: Int,
        outputFormat: ImageFormat = ImageFormat.JPEG,
        quality: Int = 85
    ): ImageResult

    /**
     * Simple compress-only function (re-encode at lower quality)
     */
    fun compressOnly(
        srcBytes: ByteArray,
        outputFormat: ImageFormat = ImageFormat.JPEG,
        quality: Int = 75
    ): ImageResult

    /**
     * Crop the image (x,y,width,height) in pixels from top-left
     */
    fun crop(
        srcBytes: ByteArray,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        outputFormat: ImageFormat = ImageFormat.JPEG,
        quality: Int = 90
    ): ImageResult

    /**
     * Rotate image by degrees (clockwise). Supports 90/180/270 etc.
     */
    fun rotate(
        srcBytes: ByteArray,
        degrees: Int,
        outputFormat: ImageFormat = ImageFormat.JPEG,
        quality: Int = 90
    ): ImageResult

    /**
     * Utility: get natural size
     */
    fun getNaturalSize(srcBytes: ByteArray): ImageSize
}

/**
 * Common helper to calculate target dimensions for aspect-preserving resize
 */
internal fun calculateTargetDimensions(
    naturalW: Int,
    naturalH: Int,
    maxWidth: Int,
    maxHeight: Int
): Pair<Int, Int> {
    if ((maxWidth <= 0 || naturalW <= maxWidth) && (maxHeight <= 0 || naturalH <= maxHeight)) {
        return naturalW to naturalH
    }

    val widthRatio = if (maxWidth > 0) maxWidth.toFloat() / naturalW else Float.POSITIVE_INFINITY
    val heightRatio = if (maxHeight > 0) maxHeight.toFloat() / naturalH else Float.POSITIVE_INFINITY
    val scale = minOf(widthRatio, heightRatio)

    val targetW = (naturalW * scale).roundToInt().coerceAtLeast(1)
    val targetH = (naturalH * scale).roundToInt().coerceAtLeast(1)

    return targetW to targetH
}
