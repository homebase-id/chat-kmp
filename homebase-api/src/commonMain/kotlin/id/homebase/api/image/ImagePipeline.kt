package id.homebase.api.image

import id.homebase.api.file.FileOperationsProvider
import kotlinx.coroutines.CancellationException
import co.touchlab.kermit.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.time.TimeSource

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
    /**
     * The image people look at: when the run has a source gain map, it's an Ultra HDR JPEG whatever [format] says,
     * so it shows bright on an HDR screen and as itself elsewhere.
     */
    val keepsHdr: Boolean = false,
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
 *   `min(minLongEdge, displayedSize's long edge)`. A decoder's own shrink is a block average, so it only shrinks
 *   while it leaves at least [HALVE_WHILE_AT_LEAST] times [minLongEdge] for Lanczos (libvips' rule), except where a
 *   platform's memory budget forces more (iOS over 24 MP, Android over 50 MP).
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
     * A new bitmap at the given size, leaving this one open, through a Lanczos filter whose reach
     * grows with the reduction so nothing aliases at any ratio. The result is 8-bit premultiplied in
     * this bitmap's colour space, with no colour above its alpha.
     */
    suspend fun resampledTo(width: Int, height: Int): PipelineBitmap

    /** The SDR pixels only: a gain map the platform decoder kept is never written (see [HdrGainmap]). */
    fun encode(format: ImageFormat, quality: Int): ByteArray

    /** This bitmap's pixels in sRGB: itself if they already are, otherwise a new bitmap, leaving this one open. */
    fun inSrgb(): PipelineBitmap
}

internal expect fun openImageSource(bytes: ByteArray): ImageSource

/** A gain-map JPEG, decoded with its values untouched: they're data, not colour, so nothing may colour-convert them. */
internal expect fun openGainmapSource(bytes: ByteArray): ImageSource

/** [path] for the decoder to read itself, like its bytes would; null where [fileOps] must read the bytes. */
internal expect fun openImageFile(path: String, fileOps: FileOperationsProvider): ImageSource?

/**
 * Makes every planned image from one decode, largest to smallest, each scaled from the one above.
 * [plan] gets the displayed size, so nothing has to decode to find out what to ask for. The result
 * keeps the plan's order.
 */
suspend fun renderImages(bytes: ByteArray, plan: (ImageSize) -> List<ImageOutput>): RenderedImages =
    withContext(Dispatchers.Default) { openImageSource(bytes).use { renderImages(it, plan = plan) } }

internal suspend fun renderImages(source: ImageSource, gainmap: HdrGainmap? = null, plan: (ImageSize) -> List<ImageOutput>): RenderedImages {
    val context = currentCoroutineContext()
    return renderFrom(source, { context.ensureActive() }, gainmap, plan)
}

/**
 * The source's HDR gain map, decoded like any image (upright as the source is) and rendered with the output that
 * [ImageOutput.keepsHdr], then joined to it by [UltraHdr].
 */
internal class HdrGainmap(val image: ImageSource, val metadata: GainmapMetadata) : AutoCloseable {
    override fun close() = image.close()

    companion object {
        /**
         * The gain map of the Ultra HDR JPEG whose start is [header], read on its own through [readRange] (it's the
         * file's tail, a fraction of its size) and tagged with the source's orientation so it turns upright with it.
         * Null for anything else.
         */
        suspend fun of(header: ByteArray, readRange: suspend (LongRange) -> ByteArray): HdrGainmap? = try {
            find(header, readRange)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = "HdrGainmap", throwable = e) { "The gain map couldn't be read; the photo goes as SDR" }
            null
        }

        private suspend fun find(header: ByteArray, readRange: suspend (LongRange) -> ByteArray): HdrGainmap? {
            val range = UltraHdr.gainmapRange(header)
                ?: if (UltraHdr.mayHaveMpfBeyond(header)) UltraHdr.gainmapRange(readRange(0L until LONG_HEADER_BYTES)) else null
            if (range == null || range.last - range.first >= MAX_GAINMAP_BYTES) return null
            val jpeg = readRange(range)
            val metadata = UltraHdr.metadataOf(jpeg) ?: return null
            val orientation = readJpegExifOrientation(header) ?: 1
            val upright = if (orientation == 1) jpeg else ImageMetadataScrubber.withOrientation(jpeg, orientation)
            return HdrGainmap(openGainmapSource(upright), metadata)
        }

        // MPF sits after EXIF (with its thumbnail), XMP and ICC, which can outgrow a 64 KB header read.
        private const val LONG_HEADER_BYTES = 256L * 1024
        private const val MAX_GAINMAP_BYTES = 16L * 1024 * 1024
    }
}

internal suspend fun renderFrom(
    source: ImageSource,
    checkCancelled: () -> Unit = {},
    gainmap: HdrGainmap? = null,
    plan: (ImageSize) -> List<ImageOutput>,
): RenderedImages {
    val size = source.displayedSize
    val outputs = plan(size)
    if (outputs.isEmpty()) return RenderedImages(size, emptyList())

    val largestFirst = outputs.withIndex().sortedByDescending { longEdge(it.value) }
    val rendered = arrayOfNulls<RenderedImage>(outputs.size)
    val started = TimeSource.Monotonic.markNow()
    var decoded: ImageSize? = null
    try {
        var current = source.decode(longEdge(largestFirst.first().value))
        decoded = ImageSize(current.width, current.height)
        try {
            for ((index, output) in largestFirst) {
                checkCancelled()
                val scaled = scaleDown(current, output.width, output.height)
                if (scaled !== current) current.close()
                current = scaled
                rendered[index] = gainmap?.takeIf { output.keepsHdr }?.let { hdrWithinLimit(current, output, it, size) } ?: when {
                    output.keepColorProfile -> encodeWithin(current, output)
                    else -> encodeInSrgb(current, output)
                }
            }
        } finally {
            current.close()
        }
    } finally {
        // In a finally, so a decode that runs out of memory still says what it was asked to decode.
        Logger.d(tag = "ImagePipeline") {
            "${size.pixelWidth}x${size.pixelHeight} decoded at ${decoded?.let { "${it.pixelWidth}x${it.pixelHeight}" } ?: "(failed)"} -> " +
                "${rendered.filterNotNull().joinToString { "${it.width}x${it.height}" }} in ${started.elapsedNow().inWholeMilliseconds} ms"
        }
    }
    return RenderedImages(size, rendered.map { it!! })
}

// A block shrink (a 2x2 average, a decoder's sampling) runs only while it leaves Lanczos at least the last 2x.
internal const val HALVE_WHILE_AT_LEAST = 4

/**
 * Halves each axis while it's at least 4x too big, then Lanczos makes the last 2-4x: a 2x2 average
 * lets detail just above its new limit through, so it must never be the last step.
 * Returns [source] when it's already the right size. Intermediate bitmaps are closed; [source] is
 * left to the caller.
 */
internal suspend fun scaleDown(source: PipelineBitmap, width: Int, height: Int): PipelineBitmap {
    var current = source
    while (true) {
        val nextWidth = if (current.width >= width * HALVE_WHILE_AT_LEAST) (current.width + 1) / 2 else current.width
        val nextHeight = if (current.height >= height * HALVE_WHILE_AT_LEAST) (current.height + 1) / 2 else current.height
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

// HDR never costs pixels: an Ultra HDR JPEG over the output's limit, or a gain map that won't render, makes it the SDR
// image at full size instead.
private suspend fun hdrWithinLimit(bitmap: PipelineBitmap, output: ImageOutput, gainmap: HdrGainmap, source: ImageSize): RenderedImage? {
    val primary = bitmap.encode(ImageFormat.JPEG, output.quality)
    if (primary.size >= output.maxBytes) return null
    val mapJpeg = try {
        renderedGainmap(gainmap.image, bitmap.width * 1.0 / source.pixelWidth, bitmap.height * 1.0 / source.pixelHeight, output.quality)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(tag = "HdrGainmap", throwable = e) { "The gain map couldn't be rendered; the image goes as SDR" }
        return null
    }
    val bytes = UltraHdr.join(primary, mapJpeg, gainmap.metadata)
    return RenderedImage(bytes, bitmap.width, bitmap.height, output.quality, ImageFormat.JPEG).takeIf { bytes.size <= output.maxBytes }
}

// The gain map keeps its share of the source's size, as the camera made it.
private suspend fun renderedGainmap(map: ImageSource, xScale: Double, yScale: Double, quality: Int): ByteArray {
    val width = max(1, (map.displayedSize.pixelWidth * xScale).toInt())
    val height = max(1, (map.displayedSize.pixelHeight * yScale).toInt())
    val decoded = map.decode(max(width, height))
    val scaled = try {
        scaleDown(decoded, width, height)
    } catch (e: Throwable) {
        decoded.close()
        throw e
    }
    if (scaled !== decoded) decoded.close()
    return scaled.use { it.encode(ImageFormat.JPEG, quality) }
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
