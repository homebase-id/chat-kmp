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
    private val header: () -> ByteArray,
    private val onClose: () -> Unit = {},
) : ImageSource {
    override fun decode(minLongEdge: Int): PipelineBitmap {
        val image = ImageIoDecode.decode(source, imageIoDecodeEdge(displayedSize, minLongEdge))
            ?: return fallback().use { it.decode(minLongEdge) }
        return SkiaPipelineBitmap(image)
    }

    override fun auxiliaryGainmap(): HdrGainmap? = source.auxiliaryGainmap(header)

    // The header read only for a comment to put right, so a backup doesn't read a megabyte more of every photo.
    override fun metadata(): ImageMetadata? = source.readMetadata()?.let { if (it.userComment == null) it else it.withJpegComment(header()) }

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
    return ImageIoSource(source, displayed, fallback = { SkiaImageSource(bytes) }, header = { bytes })
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
    val header = {
        FileSystem.SYSTEM.read(path.toPath()) {
            request(HEADER_BYTES)
            readByteArray(minOf(buffer.size, HEADER_BYTES))
        }
    }
    return ImageIoSource(source, displayed, fallback = readWhole, header = header, onClose = closeScope)
}

// An HEIC's meta box, which holds its ISO tone map, comes before the image data and is a few KB to a few hundred.
private const val HEADER_BYTES = 1024L * 1024

// About 400 MB of pixels: a 48 MP photo decodes in full.
private const val FULL_DECODE_PIXEL_LIMIT = 100_000_000L

/**
 * The long edge to ask ImageIO for. Its shrink is a block average, so it leaves Lanczos at least the last 2x; the
 * memory guard only shrinks a decode over [FULL_DECODE_PIXEL_LIMIT], and only where that's still a 2x step.
 */
internal fun imageIoDecodeEdge(size: ImageSize, minLongEdge: Int): Int {
    val longEdge = max(size.pixelWidth, size.pixelHeight)
    val guard = memoryGuardScale(size, minLongEdge)
    val coarse = when {
        longEdge >= minLongEdge.toLong() * HALVE_WHILE_AT_LEAST -> minLongEdge * 2
        guard != null && longEdge >= minLongEdge.toLong() * 2 -> longEdge / 2
        else -> longEdge
    }
    val guarded = guard?.let { ceil(longEdge * it).toInt() }
    return if (guarded != null && guarded <= longEdge / 2) minOf(coarse, guarded) else coarse
}

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
