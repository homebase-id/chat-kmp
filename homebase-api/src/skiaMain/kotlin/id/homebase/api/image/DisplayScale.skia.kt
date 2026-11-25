package id.homebase.api.image

import org.jetbrains.skia.Image
import kotlin.math.max

/**
 * [bytes] decoded upright, as the uploads decode them, and shrunk by [scaleDown] to the size [sizeFor] picks for
 * the displayed size; at the decoded size when that pick isn't smaller. Throws on bytes the platform can't read.
 */
suspend fun decodeForDisplay(bytes: ByteArray, sizeFor: (ImageSize) -> ImageSize): Image =
    openImageSource(bytes).use { source ->
        val target = sizeFor(source.displayedSize)
        val decoded = source.decode(max(target.pixelWidth, target.pixelHeight))
        if (target.pixelWidth >= decoded.width && target.pixelHeight >= decoded.height) return@use decoded.image
        val scaled = try {
            scaleDown(decoded, target.pixelWidth, target.pixelHeight)
        } catch (e: Throwable) {
            decoded.close()
            throw e
        }
        if (scaled !== decoded) decoded.close()
        scaled.image
    }

private val PipelineBitmap.image get() = (this as SkiaPipelineBitmap).image
