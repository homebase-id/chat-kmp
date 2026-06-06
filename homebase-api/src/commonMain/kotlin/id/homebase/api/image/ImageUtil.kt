package id.homebase.api.image

import androidx.compose.ui.graphics.ImageBitmap
import id.homebase.api.image.draw.StrokeCommand
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

    /**
     * Decode [srcBytes] and report whether ANY sampled pixel is non-opaque
     * (alpha below an opaque threshold). Used to auto-detect sticker / cut-out
     * images at send time so the bubble can drop its opaque backdrop.
     *
     * Callers MUST pre-gate by format — only call this for formats that can
     * carry alpha (PNG / WebP) — to keep it cheap and avoid decoding JPEGs.
     *
     * Sampling is coarse (a bounded grid plus the four corners + centre, where
     * cut-out stickers carry their transparency) so a huge image stays fast; a
     * single transparent corner is therefore reliably detected. The probe is
     * defensive: on ANY decode failure it returns `false` (opaque) so we never
     * accidentally strip a real photo's backdrop.
     */
    fun hasNonOpaquePixels(srcBytes: ByteArray): Boolean

    /**
     * Apply an arbitrary affine transform to [srcBytes] and rasterize the
     * result into a new image of [outputWidth] x [outputHeight] pixels.
     *
     * [matrix9] is a 9-element row-major affine matrix that maps natural-pixel
     * coordinates of the source image into pixel coordinates of the output:
     *
     *     [ MSCALE_X  MSKEW_X   MTRANS_X ]
     *     [ MSKEW_Y   MSCALE_Y  MTRANS_Y ]
     *     [    0         0         1     ]
     *
     * Pixels outside the warped source region are filled with [fillColorArgb].
     */
    fun warpAffine(
        srcBytes: ByteArray,
        matrix9: FloatArray,
        outputWidth: Int,
        outputHeight: Int,
        fillColorArgb: Int = 0x00000000,
        outputFormat: ImageFormat = ImageFormat.JPEG,
        quality: Int = 90,
    ): ImageResult

    /**
     * Decode [srcBytes], paint each [StrokeCommand] over the image at its
     * natural pixel resolution, and re-encode. Stroke coordinates are in
     * source-pixel space (origin top-left, +y down).
     *
     * Used by the draw editor's finalizer. PAINT strokes are filled with
     * their colour; BLUR strokes mask a pre-blurred copy of the source
     * onto the output (manual blur brush).
     */
    fun drawStrokes(
        srcBytes: ByteArray,
        strokes: List<StrokeCommand>,
        outputFormat: ImageFormat = ImageFormat.JPEG,
        quality: Int = 90,
    ): ImageResult

    /**
     * Decode [srcBytes], blur via stack-blur, re-encode. Output is
     * lossless PNG by default — the result feeds the draw editor's
     * on-screen preview overlay where JPEG artifacts would be visible.
     */
    fun blurBytes(
        srcBytes: ByteArray,
        radius: Int = 25,
        outputFormat: ImageFormat = ImageFormat.PNG,
        quality: Int = 100,
    ): ImageResult

    /**
     * Rasterize an SVG document into a bitmap.
     *
     * Scales the SVG into a box of [maxDim] × [maxDim] preserving aspect
     * ratio, renders it, and encodes the result in [outputFormat] at the
     * given [quality]. The returned [ImageResult.naturalSize] is the
     * SVG's intrinsic (vector) size; [ImageResult.size] is the rendered
     * pixel size.
     *
     * Suspending because the Web actual awaits an async browser
     * `Image.onload` / `OffscreenCanvas.convertToBlob`. JVM, Native and
     * Android impls don't suspend in practice.
     *
     * Throws on parse / render failure — callers (e.g.
     * [createThumbnails]) should wrap in try/catch and fall back to
     * a no-thumb result so a malformed SVG can't break the upload.
     */
    suspend fun rasterizeSvg(
        svgBytes: ByteArray,
        maxDim: Int,
        outputFormat: ImageFormat = ImageFormat.WEBP,
        quality: Int = 76,
    ): ImageResult
}

/**
 * Convert HEIC/HEIF image bytes to JPEG bytes.
 * Returns null if conversion is not supported or fails on this platform.
 */
expect fun convertHeicToJpeg(heicBytes: ByteArray): ByteArray?

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
