@file:OptIn(ExperimentalForeignApi::class)

package id.homebase.api.image

import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.accessSecurityScope
import id.homebase.api.file.isPhotoLibraryPath
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sqrt
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.CoreFoundation.CFRelease
import platform.ImageIO.CGImageSourceRef

/**
 * ImageIO decodes HEIC and other camera formats directly and only as large as asked, with no JPEG
 * bridge. It hands over to Skia's bitmap for scaling and encoding, so those match the other targets.
 * [fallback], a [SkiaImageSource], takes over for anything ImageIO can't read.
 */
private class ImageIoSource(
    private val source: CGImageSourceRef,
    override val displayedSize: ImageSize,
    private val fallback: () -> ImageSource,
    private val onClose: () -> Unit = {},
) : ImageSource {
    override fun decode(minLongEdge: Int): PipelineBitmap {
        // ImageIO's shrink is a block average that leaves Lanczos at least 2x; only the memory guard forces less.
        val longEdge = max(displayedSize.pixelWidth, displayedSize.pixelHeight)
        val guard = memoryGuardScale(displayedSize, minLongEdge)
        val coarse = when {
            longEdge >= minLongEdge.toLong() * HALVE_WHILE_AT_LEAST -> minLongEdge * 2
            guard != null && longEdge >= minLongEdge.toLong() * 2 -> longEdge / 2
            else -> longEdge
        }
        val coarseEdge = guard?.let { minOf(coarse, ceil(longEdge * it).toInt()) } ?: coarse
        val image = ImageIoDecode.decode(source, coarseEdge) ?: return fallback().use { it.decode(minLongEdge) }
        return SkiaPipelineBitmap(image)
    }

    override fun auxiliaryGainmap(): HdrGainmap? = source.auxiliaryGainmap()

    override fun metadata(): ImageMetadata? = source.readMetadata()

    override fun close() {
        try {
            CFRelease(source)
        } finally {
            onClose()
        }
    }
}

private fun openWithImageIo(bytes: ByteArray): ImageSource? {
    val source = ImageIoDecode.open(bytes) ?: return null
    val displayed = ImageIoDecode.displayedSize(source)
    if (displayed == null) {
        CFRelease(source)
        return null
    }
    return ImageIoSource(source, displayed, fallback = { SkiaImageSource(bytes) })
}

internal actual fun openImageFile(path: String, fileOps: FileOperationsProvider): ImageSource? {
    if (isPhotoLibraryPath(path)) return null
    val closeScope = accessSecurityScope(path)
    val source = ImageIoDecode.open(path)
    val displayed = source?.let(ImageIoDecode::displayedSize)
    if (source == null || displayed == null) {
        source?.let(::CFRelease)
        closeScope()
        return null
    }
    val readWhole = { SkiaImageSource(FileSystem.SYSTEM.read(path.toPath()) { readByteArray() }) }
    return ImageIoSource(source, displayed, fallback = readWhole, onClose = closeScope)
}

// About 96 MB of pixels. A 12 MP photo stays a full, clean decode; a 48 MP one doesn't.
private const val FULL_DECODE_PIXEL_LIMIT = 24_000_000L

/**
 * How far to shrink a decode that would otherwise pass [FULL_DECODE_PIXEL_LIMIT], or null to leave
 * it alone. A memory guard, not a quality step: it never goes below what the largest output needs.
 */
private fun memoryGuardScale(size: ImageSize, minLongEdge: Int): Double? {
    val pixels = size.pixelWidth.toDouble() * size.pixelHeight
    val longEdge = max(size.pixelWidth, size.pixelHeight)
    if (pixels <= FULL_DECODE_PIXEL_LIMIT || longEdge <= minLongEdge) return null
    val smallest = minLongEdge.toDouble() / longEdge
    return max(smallest, sqrt(FULL_DECODE_PIXEL_LIMIT / pixels))
}

internal actual fun openImageSource(bytes: ByteArray): ImageSource =
    openWithImageIo(bytes) ?: SkiaImageSource(bytes)
