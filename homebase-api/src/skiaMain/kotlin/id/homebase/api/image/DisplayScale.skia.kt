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
 * Decodes whose Lanczos step reads more than [DISPLAY_LARGE_DECODE_PIXELS] run one at a time.
 */
suspend fun decodeForDisplay(bytes: ByteArray, sizeFor: (ImageSize) -> ImageSize): Image =
    decodeForDisplay(bytes, DISPLAY_LARGE_DECODE_PIXELS, sizeFor)

internal suspend fun decodeForDisplay(bytes: ByteArray, largePixels: Long, sizeFor: (ImageSize) -> ImageSize): Image =
    withContext(Dispatchers.Default) {
        openImageSource(bytes).use { source ->
            val target = sizeFor(source.displayedSize)
            if (lanczosInputPixels(source.displayedSize, target) > largePixels) {
                largeDecodes.withLock { decodeAndShrink(source, target) }
            } else {
                decodeAndShrink(source, target)
            }
        }
    }

// A 48 MP photo shown at 4096 px took +344 MB of heap and 4.5 s on desktop; several at once could run out.
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

// What scaleDown's last step reads, after the halvings it does while an edge is at least 4x too big.
private fun lanczosInputPixels(size: ImageSize, target: ImageSize): Long {
    var width = size.pixelWidth
    var height = size.pixelHeight
    while (width >= target.pixelWidth * HALVE_WHILE_AT_LEAST) width = (width + 1) / 2
    while (height >= target.pixelHeight * HALVE_WHILE_AT_LEAST) height = (height + 1) / 2
    return width.toLong() * height
}

private val PipelineBitmap.image get() = (this as SkiaPipelineBitmap).image
