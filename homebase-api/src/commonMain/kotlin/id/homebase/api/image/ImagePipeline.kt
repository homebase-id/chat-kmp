package id.homebase.api.image

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** One image a pipeline run makes. The caller works out the size; the pipeline never chooses it. */
data class ImageOutput(
    val width: Int,
    val height: Int,
    val quality: Int,
    val maxBytes: Int,
    val format: ImageFormat = ImageFormat.WEBP,
    /** Below this, an image over [maxBytes] shrinks instead of losing more quality. */
    val minQuality: Int = DEFAULT_MIN_QUALITY,
    /** False converts to sRGB first, for outputs too small to afford an embedded colour profile. */
    val keepColorProfile: Boolean = true,
    val overLimit: OverLimit = OverLimit.LOWER_QUALITY,
    /** The size to fall back to first when over the limit, if this output was enlarged past its row. */
    val rowSize: ImageSize? = null,
) {
    companion object {
        const val DEFAULT_MIN_QUALITY = 40
    }
}

enum class OverLimit {
    /** For placeholders and budgeted images, where bytes matter more than detail. */
    LOWER_QUALITY,

    /** For thumbnails with a final image behind them: fewer pixels show less than blockiness does. */
    SHRINK,
}

class RenderedImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    /** The quality actually used, which is below the requested one if the image had to fit a limit. */
    val quality: Int,
    val format: ImageFormat,
)

class RenderedImages(val displayedSize: ImageSize, val images: List<RenderedImage>)

/**
 * An encoded image that hasn't had its pixels decoded.
 *
 * Contract for every platform:
 * - [displayedSize] is the size after EXIF rotation, read without decoding the pixels.
 * - [decode] decodes at most once per source, upright, with a long edge of at least
 *   `min(minLongEdge, displayedSize's long edge)`. Decoding less than the full image is encouraged.
 * - Both throw on bytes the platform can't read.
 * - [metadata] reads the file's EXIF without decoding pixels, or is null where the platform can't.
 */
internal interface ImageSource : AutoCloseable {
    val displayedSize: ImageSize

    fun decode(minLongEdge: Int): PipelineBitmap

    fun metadata(): ImageMetadata?
}

/**
 * Decoded pixels. Every platform must keep transparency and the source's colour: a wide-gamut image
 * either keeps its profile through [encode] or is converted, never relabelled.
 */
internal interface PipelineBitmap : AutoCloseable {
    val width: Int
    val height: Int

    /** True for sRGB and untagged pixels, whose files need no colour profile. */
    val isSrgb: Boolean

    /**
     * A new bitmap at the given size, filtered at least bilinearly, leaving this one open.
     * Only asked for exact halvings, where bilinear averages every pixel.
     */
    fun scaledTo(width: Int, height: Int): PipelineBitmap

    /**
     * A new bitmap at the given size, leaving this one open. A Lanczos filter whose reach grows with
     * the reduction, so nothing aliases at any ratio, run on premultiplied pixels in this bitmap's
     * colour space, with no colour left above its alpha. Phones resample in native memory.
     */
    suspend fun resampledTo(width: Int, height: Int): PipelineBitmap

    fun encode(format: ImageFormat, quality: Int): ByteArray

    /** This bitmap's pixels in sRGB: itself if they already are, otherwise a new bitmap, leaving this one open. */
    fun inSrgb(): PipelineBitmap
}

internal expect fun openImageSource(bytes: ByteArray): ImageSource

/**
 * The image at [path] without reading the file into memory: the decoder reads only what it needs.
 * Null where the platform decodes only bytes, or can't open [path] itself (a Photos-library asset),
 * so the caller reads the bytes instead. A source from a path behaves exactly like one from its bytes.
 */
internal expect fun openImageFile(path: String): ImageSource?

/**
 * Makes every planned image from one decode, largest to smallest, each scaled from the one above.
 * [plan] gets the displayed size, so nothing has to decode to find out what to ask for. The result
 * keeps the plan's order.
 */
suspend fun renderImages(bytes: ByteArray, plan: (ImageSize) -> List<ImageOutput>): RenderedImages =
    withContext(Dispatchers.Default) {
        val context = coroutineContext
        openImageSource(bytes).use { renderFrom(it, { context.ensureActive() }, plan) }
    }

internal suspend fun renderFrom(
    source: ImageSource,
    checkCancelled: () -> Unit = {},
    plan: (ImageSize) -> List<ImageOutput>,
): RenderedImages {
    val size = source.displayedSize
    val outputs = plan(size)
    if (outputs.isEmpty()) return RenderedImages(size, emptyList())

    val largestFirst = outputs.withIndex().sortedByDescending { longEdge(it.value) }
    val rendered = arrayOfNulls<RenderedImage>(outputs.size)
    var current = source.decode(longEdge(largestFirst.first().value))
    try {
        for ((index, output) in largestFirst) {
            checkCancelled()
            val scaled = scaleDown(current, output.width, output.height)
            if (scaled !== current) current.close()
            current = scaled
            rendered[index] = if (output.keepColorProfile) {
                encodeWithin(current, output)
            } else {
                encodeInSrgb(current, output)
            }
        }
    } finally {
        current.close()
    }
    return RenderedImages(size, rendered.map { it!! })
}

/**
 * Halves each axis while it's at least 2x too big, because an exact 2x step averages every source
 * pixel cheaply, then finishes with Lanczos, the only filter here that doesn't alias under 2x.
 * Returns [source] when it's already the right size. Intermediate bitmaps are closed; [source] is
 * left to the caller.
 */
internal suspend fun scaleDown(source: PipelineBitmap, width: Int, height: Int): PipelineBitmap {
    var current = source
    while (true) {
        val nextWidth = if (current.width >= width * 2) (current.width + 1) / 2 else current.width
        val nextHeight = if (current.height >= height * 2) (current.height + 1) / 2 else current.height
        if (nextWidth == current.width && nextHeight == current.height) break
        val exact = (nextWidth == current.width || current.width % 2 == 0) && (nextHeight == current.height || current.height % 2 == 0)
        current = current.replacedBy(source) {
            if (exact) it.scaledTo(nextWidth, nextHeight) else it.resampledTo(nextWidth, nextHeight)
        }
    }
    if (current.width == width && current.height == height) return current
    return current.replacedBy(source) { it.resampledTo(width, height) }
}

// The finally is what frees an intermediate when the next step runs out of memory.
private inline fun PipelineBitmap.replacedBy(
    keepOpen: PipelineBitmap,
    next: (PipelineBitmap) -> PipelineBitmap,
): PipelineBitmap = try {
    next(this)
} finally {
    if (this !== keepOpen) close()
}

private suspend fun encodeInSrgb(bitmap: PipelineBitmap, output: ImageOutput): RenderedImage {
    val srgb = bitmap.inSrgb()
    try {
        return encodeWithin(srgb, output)
    } finally {
        if (srgb !== bitmap) srgb.close()
    }
}

private suspend fun encodeWithin(bitmap: PipelineBitmap, output: ImageOutput): RenderedImage = when (output.overLimit) {
    OverLimit.LOWER_QUALITY -> encodeLoweringQuality(bitmap, output)
    OverLimit.SHRINK -> encodeShrinking(bitmap, output)
}

private fun encoded(bitmap: PipelineBitmap, format: ImageFormat, quality: Int): ByteArray {
    val bytes = bitmap.encode(format, quality)
    return if (format == ImageFormat.WEBP && bitmap.isSrgb) stripWebpColorProfile(bytes) else bytes
}

/**
 * Encodes from the pixels at [ImageOutput.quality]; over the limit, lowers quality from the same
 * pixels, then shrinks, re-checking each time. A 1x1 image is the end of the line, which is what
 * makes the tiny preview's limit a real guarantee.
 */
private suspend fun encodeLoweringQuality(bitmap: PipelineBitmap, output: ImageOutput): RenderedImage {
    val maxBytes = output.maxBytes
    val floor = output.minQuality.coerceIn(1, 100)
    var quality = output.quality.coerceIn(1, 100)
    var bytes = encoded(bitmap, output.format, quality)
    while (bytes.size > maxBytes && quality > floor) {
        quality = nextQuality(quality, bytes.size, maxBytes).coerceAtLeast(floor)
        bytes = encoded(bitmap, output.format, quality)
    }

    var current = bitmap
    try {
        while (bytes.size > maxBytes && (current.width > 1 || current.height > 1)) {
            val nextWidth = shrink(current.width)
            val nextHeight = shrink(current.height)
            val next = scaleDown(current, nextWidth, nextHeight)
            if (current !== bitmap) current.close()
            current = next
            bytes = encoded(current, output.format, quality)
        }
        return RenderedImage(bytes, current.width, current.height, quality, output.format)
    } finally {
        if (current !== bitmap) current.close()
    }
}

/** Over the limit, keeps the quality: back to the row's own size if it was enlarged, then smaller by an estimate. */
private suspend fun encodeShrinking(bitmap: PipelineBitmap, output: ImageOutput): RenderedImage {
    val quality = output.quality.coerceIn(1, 100)
    var bytes = encoded(bitmap, output.format, quality)
    var current = bitmap
    try {
        var target = output.rowSize?.takeIf { bytes.size > output.maxBytes && it.pixelWidth < bitmap.width }
        while (bytes.size > output.maxBytes && (current.width > 1 || current.height > 1)) {
            val (width, height) = target?.let { it.pixelWidth to it.pixelHeight } ?: estimatedFit(current, bytes.size, output.maxBytes)
            target = null
            val next = scaleDown(bitmap, width, height)
            if (current !== bitmap) current.close()
            current = next
            bytes = encoded(current, output.format, quality)
        }
        return RenderedImage(bytes, current.width, current.height, quality, output.format)
    } finally {
        if (current !== bitmap) current.close()
    }
}

// Bytes go roughly with area, so edges go with the square root; the margin saves a re-encode.
private fun estimatedFit(bitmap: PipelineBitmap, size: Int, maxBytes: Int): Pair<Int, Int> {
    val factor = sqrt(maxBytes.toDouble() / size) * SHRINK_MARGIN
    return max(1, min(bitmap.width - 1, (bitmap.width * factor).toInt())) to
        max(1, min(bitmap.height - 1, (bitmap.height * factor).toInt()))
}

private fun shrink(edge: Int) = max(1, edge * 3 / 4)

// About 96 MB of pixels. A 12 MP photo stays a full, clean decode; a 48 MP one doesn't.
internal const val FULL_DECODE_PIXEL_LIMIT = 24_000_000L

/**
 * How far to shrink a decode that would otherwise pass [FULL_DECODE_PIXEL_LIMIT], or null to leave
 * it alone. A memory guard, not a quality step: it never goes below what the largest output needs.
 */
internal fun memoryGuardScale(size: ImageSize, sampleSize: Int, minLongEdge: Int): Double? {
    val pixels = size.pixelWidth.toDouble() * size.pixelHeight / (sampleSize * sampleSize)
    val longEdge = max(size.pixelWidth, size.pixelHeight)
    if (pixels <= FULL_DECODE_PIXEL_LIMIT || longEdge <= minLongEdge) return null
    val smallest = minLongEdge.toDouble() / longEdge
    return max(smallest, min(sqrt(FULL_DECODE_PIXEL_LIMIT / pixels) / sampleSize, smallest * 2))
}

private fun longEdge(output: ImageOutput) = max(output.width, output.height)

// Further over the limit means a bigger drop, because each attempt is a full encode.
private fun nextQuality(quality: Int, size: Int, maxBytes: Int): Int {
    val excess = (size.toLong() - maxBytes) * quality / maxBytes.coerceAtLeast(1) / 2
    return quality - excess.coerceIn(MIN_QUALITY_STEP.toLong(), MAX_QUALITY_STEP.toLong()).toInt()
}

private const val SHRINK_MARGIN = 0.95
private const val MIN_QUALITY_STEP = 8
private const val MAX_QUALITY_STEP = 30

/** The one aspect-preserving resize behind [ImageUtils.resizePreserveAspect] on every platform. */
internal suspend fun resizeThroughPipeline(
    srcBytes: ByteArray,
    maxWidth: Int,
    maxHeight: Int,
    outputFormat: ImageFormat,
    quality: Int,
): ImageResult = openImageSource(srcBytes).use { source ->
    val natural = source.displayedSize
    val image = renderFrom(source) {
        val (width, height) = calculateTargetDimensions(natural.pixelWidth, natural.pixelHeight, maxWidth, maxHeight)
        listOf(ImageOutput(width, height, quality, Int.MAX_VALUE, outputFormat))
    }.images.single()
    ImageResult(image.bytes, natural, ImageSize(image.width, image.height))
}
