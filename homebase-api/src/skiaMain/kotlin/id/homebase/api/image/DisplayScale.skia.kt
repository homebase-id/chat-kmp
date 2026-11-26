package id.homebase.api.image

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import kotlin.math.max

/**
 * [bytes] decoded upright, as the uploads decode them, and shrunk by [scaleDown] to the size [sizeFor] picks for
 * the displayed size; at the decoded size when that pick isn't smaller. Throws on bytes the platform can't read.
 * Decodes in which Lanczos reads more than [DISPLAY_LARGE_DECODE_PIXELS] run one at a time.
 */
suspend fun decodeForDisplay(bytes: ByteArray, sizeFor: (ImageSize) -> ImageSize): Image =
    decodeForDisplay(bytes, DISPLAY_LARGE_DECODE_PIXELS, sizeFor)

internal suspend fun decodeForDisplay(bytes: ByteArray, largePixels: Long, sizeFor: (ImageSize) -> ImageSize): Image =
    // Opened on the caller's dispatcher: desktop's HEIC conversion waits on ffmpeg.
    openImageSource(bytes).use { source ->
        val target = sizeFor(source.displayedSize)
        require(target.pixelWidth > 0 && target.pixelHeight > 0) { "Can't show an image at $target" }
        withContext(Dispatchers.Default) {
            if (largestLanczosInput(source.displayedSize, target) > largePixels) {
                largeDecodes.withLock { decodeAndShrink(source, target) }
            } else {
                decodeAndShrink(source, target)
            }
        }
    }

// The Kotlin Lanczos (desktop, web) copies its input to the heap, so several large ones at once can exhaust it.
internal const val DISPLAY_LARGE_DECODE_PIXELS = 24_000_000L

private val largeDecodes = Mutex()

private suspend fun decodeAndShrink(source: ImageSource, target: ImageSize): Image {
    val decoded = source.decode(max(target.pixelWidth, target.pixelHeight))
    if (target.pixelWidth >= decoded.width && target.pixelHeight >= decoded.height) return decoded.image
    val scaled = try {
        scaleDown(decoded, target.pixelWidth, target.pixelHeight)
    } catch (e: Throwable) {
        decoded.close()
        throw e
    }
    if (scaled !== decoded) decoded.close()
    return scaled.image
}

// Mirrors scaleDown's steps: a halving with an odd edge, and the last step, are Lanczos and read the whole input.
internal fun largestLanczosInput(size: ImageSize, target: ImageSize): Long {
    var width = size.pixelWidth
    var height = size.pixelHeight
    var largest = 0L
    while (true) {
        val nextWidth = if (width >= target.pixelWidth * HALVE_WHILE_AT_LEAST) (width + 1) / 2 else width
        val nextHeight = if (height >= target.pixelHeight * HALVE_WHILE_AT_LEAST) (height + 1) / 2 else height
        if (nextWidth == width && nextHeight == height) break
        val exact = (nextWidth == width || width % 2 == 0) && (nextHeight == height || height % 2 == 0)
        if (!exact) largest = max(largest, width.toLong() * height)
        width = nextWidth
        height = nextHeight
    }
    if (width != target.pixelWidth || height != target.pixelHeight) largest = max(largest, width.toLong() * height)
    return largest
}

private val PipelineBitmap.image get() = (this as SkiaPipelineBitmap).image
