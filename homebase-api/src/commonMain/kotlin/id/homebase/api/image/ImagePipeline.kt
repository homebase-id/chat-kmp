package id.homebase.api.image

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/** One image a pipeline run makes. The caller works out the size; the pipeline never chooses it. */
data class ImageOutput(
    val width: Int,
    val height: Int,
    val quality: Int,
    val maxBytes: Int,
    val format: ImageFormat = ImageFormat.WEBP,
    /** Below this, an image over [maxBytes] shrinks instead of losing more quality. */
    val minQuality: Int = DEFAULT_MIN_QUALITY,
) {
    companion object {
        const val DEFAULT_MIN_QUALITY = 40
    }
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
 */
internal interface ImageSource : AutoCloseable {
    val displayedSize: ImageSize

    fun decode(minLongEdge: Int): PipelineBitmap
}

/**
 * Decoded pixels. Every platform must keep transparency and the source's colour: a wide-gamut image
 * either keeps its profile through [encode] or is converted, never relabelled.
 */
internal interface PipelineBitmap : AutoCloseable {
    val width: Int
    val height: Int

    /**
     * A new bitmap at the given size, filtered at least bilinearly, leaving this one open.
     * Only asked to shrink by less than 2x per axis; [scaleDown] does the rest.
     */
    fun scaledTo(width: Int, height: Int): PipelineBitmap

    fun encode(format: ImageFormat, quality: Int): ByteArray
}

internal expect fun openImageSource(bytes: ByteArray): ImageSource

/**
 * Makes every planned image from one decode, largest to smallest, each scaled from the one above.
 * [plan] gets the displayed size, so nothing has to decode to find out what to ask for. The result
 * keeps the plan's order.
 */
suspend fun renderImages(bytes: ByteArray, plan: (ImageSize) -> List<ImageOutput>): RenderedImages =
    withContext(Dispatchers.Default) { openImageSource(bytes).use { renderFrom(it, plan) } }

internal fun renderFrom(source: ImageSource, plan: (ImageSize) -> List<ImageOutput>): RenderedImages {
    val size = source.displayedSize
    val outputs = plan(size)
    if (outputs.isEmpty()) return RenderedImages(size, emptyList())

    val largestFirst = outputs.withIndex().sortedByDescending { longEdge(it.value) }
    val rendered = arrayOfNulls<RenderedImage>(outputs.size)
    var current = source.decode(longEdge(largestFirst.first().value))
    try {
        for ((index, output) in largestFirst) {
            val scaled = scaleDown(current, output.width, output.height)
            if (scaled !== current) current.close()
            current = scaled
            rendered[index] = encodeWithin(current, output)
        }
    } finally {
        current.close()
    }
    return RenderedImages(size, rendered.map { it!! })
}

/**
 * Halves each axis while it's at least 2x too big, because a 2x bilinear step averages every source
 * pixel, where one big bilinear step samples only a few and aliases. Returns [source] when it's
 * already the right size. Intermediate bitmaps are closed; [source] is left to the caller.
 */
internal fun scaleDown(source: PipelineBitmap, width: Int, height: Int): PipelineBitmap {
    var current = source
    while (true) {
        val nextWidth = if (current.width >= width * 2) (current.width + 1) / 2 else current.width
        val nextHeight = if (current.height >= height * 2) (current.height + 1) / 2 else current.height
        if (nextWidth == current.width && nextHeight == current.height) break
        current = current.replacedBy(source) { it.scaledTo(nextWidth, nextHeight) }
    }
    if (current.width == width && current.height == height) return current
    return current.replacedBy(source) { it.scaledTo(width, height) }
}

private inline fun PipelineBitmap.replacedBy(
    keepOpen: PipelineBitmap,
    next: (PipelineBitmap) -> PipelineBitmap,
): PipelineBitmap {
    val result = next(this)
    if (this !== keepOpen) close()
    return result
}

/**
 * Encodes from the pixels at [ImageOutput.quality]; over the limit, lowers quality from the same
 * pixels, then shrinks, re-checking each time. A 1x1 image is the end of the line, which is what
 * makes the tiny preview's limit a real guarantee.
 */
private fun encodeWithin(bitmap: PipelineBitmap, output: ImageOutput): RenderedImage {
    val maxBytes = output.maxBytes
    val floor = output.minQuality.coerceIn(1, 100)
    var quality = output.quality.coerceIn(1, 100)
    var bytes = bitmap.encode(output.format, quality)
    while (bytes.size > maxBytes && quality > floor) {
        quality = (quality - QUALITY_STEP).coerceAtLeast(floor)
        bytes = bitmap.encode(output.format, quality)
    }

    var current = bitmap
    try {
        while (bytes.size > maxBytes && (current.width > 1 || current.height > 1)) {
            val nextWidth = shrink(current.width)
            val nextHeight = shrink(current.height)
            current = current.replacedBy(bitmap) { it.scaledTo(nextWidth, nextHeight) }
            bytes = current.encode(output.format, quality)
        }
        return RenderedImage(bytes, current.width, current.height, quality, output.format)
    } finally {
        if (current !== bitmap) current.close()
    }
}

private fun shrink(edge: Int) = max(1, edge * 3 / 4)

private fun longEdge(output: ImageOutput) = max(output.width, output.height)

private const val QUALITY_STEP = 8

/** The one aspect-preserving resize behind [ImageUtils.resizePreserveAspect] on every platform. */
internal fun resizeThroughPipeline(
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
