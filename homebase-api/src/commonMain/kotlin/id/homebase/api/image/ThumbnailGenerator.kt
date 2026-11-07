package id.homebase.api.image

import id.homebase.api.serialization.Base64Bytes
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// Output shape (per createThumbnails call) is the same for every input
// format: a tiny embedded webp + N additional webp thumbnail files,
// sorted by increasing size. The N differs by input characteristics:
//
//   • Raster (JPEG/PNG/WebP/HEIC): N is whatever getRevisedThumbs keeps
//     after filtering baseThumbSizes against the source's natural size.
//     Sizes >= source are dropped (or collapsed into a single synthetic
//     thumb at source size) because upscaling rasters just amplifies
//     pixels without adding detail.
//   • GIF: N = 0. The animated original travels as the payload; we only
//     produce the embedded tiny so receivers have an instant blurred
//     preview. The receiver's HomebaseImageLoader keeps "image/gif" in
//     THUMBLESS_CONTENT_TYPES and loads the full payload.
//   • SVG: N = baseThumbSizes.size (currently 4). Vector data is
//     resolution-independent, so rendering at sizes above the SVG's
//     declared intrinsic is free quality — we ship the full ladder so
//     hi-DPI viewers always get a crisp render regardless of whether
//     the author declared the SVG as 16×16, 192×192, or viewBox-only.
//
// Thumb presets
val baseThumbSizes = listOf(
    ThumbnailInstruction(quality = 84, maxPixelDimension = 320, maxBytes = 26 * 1024),
    ThumbnailInstruction(quality = 84, maxPixelDimension = 640, maxBytes = 102 * 1024),
    ThumbnailInstruction(quality = 76, maxPixelDimension = 1080, maxBytes = 291 * 1024),
    ThumbnailInstruction(quality = 76, maxPixelDimension = 1600, maxBytes = 640 * 1024)
)

val tinyThumbSize = ThumbnailInstruction(quality = 76, maxPixelDimension = 20, maxBytes = 768)

// MediaQuality.STANDARD ships the 1600px encode as the payload itself, so the ladder above it
// would be duplicate bytes. 640 stays because the receiver's selectThumbSize clamps to the
// largest thumb and never escalates to the payload — a 320-only ladder upscales every bubble.
val standardThumbSizes = baseThumbSizes.take(2)

// q85 rather than the ladder's q76: in STANDARD this is the largest artifact a photo has.
// The 640 KB cap is the valve for a pathologically detailed frame; at q85/1600 it rarely bites.
val standardPrimaryImage =
    ThumbnailInstruction(quality = 85, maxPixelDimension = 1600, maxBytes = 640 * 1024)

@OptIn(ExperimentalEncodingApi::class)

fun getRevisedThumbs(sourceSize: ImageSize, thumbs: List<ThumbnailInstruction>): List<ThumbnailInstruction> {
    val sourceMax = max(sourceSize.pixelWidth, sourceSize.pixelHeight)
    val thresholdMin = ((90 * sourceMax) / 100.0).roundToInt()
    val thresholdMax = ((110 * sourceMax) / 100.0).roundToInt()

    val keptThumbs = thumbs.filter {
        it.maxPixelDimension <= sourceMax &&
                (it.maxPixelDimension < thresholdMin || it.maxPixelDimension > thresholdMax)
    }.toMutableList()

    if (keptThumbs.size < thumbs.size) {
        val nearest = thumbs.minByOrNull { abs(it.maxPixelDimension - sourceMax) }
        val maxBytes = if (nearest != null) {
            val scale = sourceMax.toDouble() / nearest.maxPixelDimension.toDouble()
            var candidate = (nearest.maxBytes * scale).roundToInt()
            candidate = candidate.coerceIn(10 * 1024, 1024 * 1024)
            candidate
        } else {
            300 * 1024
        }

        val q = if (sourceMax <= 640) 84 else 76
        keptThumbs.add(
            ThumbnailInstruction(
                quality = q,
                maxPixelDimension = sourceMax,
                maxBytes = maxBytes
            )
        )
    }

    return keptThumbs.sortedBy { it.maxPixelDimension }
}

/**
 * Main entry. Everything comes from one decode through [renderImages].
 *
 * The second slot is null only when an SVG can't be rendered: receivers gate the bubble image block
 * on a `hasImage` flag in the descriptor, so a missing thumb renders as "no image" rather than a
 * warning triangle.
 */
suspend fun createThumbnails(
    imageBytes: ByteArray,
    payloadKey: String,
    thumbSizes: List<ThumbnailInstruction>? = null
): Triple<ImageSize, EmbeddedThumb?, List<ThumbnailFile>> = withContext(Dispatchers.Default) {

    // Checked before decoding, because the raster decoders can't read SVG.
    val header = imageBytes.take(16).toByteArray().decodeToString().lowercase()
    val isSvg = header.contains("<svg") || header.contains("<?xml")
    val isGif = imageBytes.size >= 3 && imageBytes[0] == 0x47.toByte() /* G */ && imageBytes[1] == 0x49.toByte() /* I */ && imageBytes[2] == 0x46.toByte() /* F */

    val requestedSizes = thumbSizes ?: baseThumbSizes

    if (isSvg) {
        // Vector data is resolution-independent, so unlike rasters every size is rendered, however
        // small the SVG declares itself: a 16x16 favicon still gets a crisp 1600px thumb.
        return@withContext try {
            val naturalSize = getSvgDimensions(imageBytes) ?: ImageSize(320, 320)
            val largest = maxOf(tinyThumbSize.maxPixelDimension, requestedSizes.maxOfOrNull { it.maxPixelDimension } ?: 0)
            val rasterized = ImageUtils.rasterizeSvg(imageBytes, largest, ImageFormat.PNG, 100)
            val rendered = renderImages(rasterized.bytes) { raster ->
                (requestedSizes + tinyThumbSize).map { it.toOutput(raster, isTiny = it === tinyThumbSize) }
            }.images
            Triple(naturalSize, rendered.last().toEmbedded(), rendered.dropLast(1).map { it.toThumbnailFile(payloadKey) })
        } catch (e: Exception) {
            // No thumb is better than a stuck outbox.
            val naturalSize = getSvgDimensions(imageBytes) ?: ImageSize(320, 320)
            Triple(naturalSize, null, emptyList())
        }
    }

    val result = renderImages(imageBytes) { natural ->
        val thumbs = if (isGif) emptyList() else getRevisedThumbs(natural, requestedSizes)
        (thumbs + tinyThumbSize).map { it.toOutput(natural, isTiny = it === tinyThumbSize) }
    }
    val tiny = result.images.last()
    val thumbnails = result.images.dropLast(1).map { it.toThumbnailFile(payloadKey) }
    val natural = result.displayedSize

    // Receivers lay a GIF out from the tiny's size, so it reports the natural one.
    val embeddedTiny = if (isGif) {
        EmbeddedThumb(natural.pixelWidth, natural.pixelHeight, "image/webp", Base64Bytes(tiny.bytes))
    } else {
        tiny.toEmbedded()
    }
    return@withContext Triple(natural, embeddedTiny, thumbnails)
}

/** The [tinyThumbSize] webp a file embeds in its metadata, sized as the tiny itself (unlike the GIF case above). */
suspend fun createTinyPreview(imageBytes: ByteArray, payloadKey: String): EmbeddedThumb =
    renderImages(imageBytes) { listOf(tinyThumbSize.toOutput(it, isTiny = true)) }.images.single().toEmbedded()

/**
 * Create a single thumbnail according to [instruction], fitting inside maxPixelDimension on both
 * axes and within maxBytes. A caller making several from the same image should use [renderImages],
 * so it decodes once.
 */
suspend fun createImageThumbnail(
    imageBytes: ByteArray,
    payloadKey: String,
    instruction: ThumbnailInstruction,
    isTinyThumb: Boolean = false
): ThumbnailFile = renderImages(imageBytes) { listOf(instruction.toOutput(it, isTinyThumb)) }
    .images.single().toThumbnailFile(payloadKey)

private fun ThumbnailInstruction.toOutput(source: ImageSize, isTiny: Boolean): ImageOutput {
    val (width, height) = calculateTargetDimensions(source.pixelWidth, source.pixelHeight, maxPixelDimension, maxPixelDimension)
    return ImageOutput(
        width = width,
        height = height,
        quality = quality,
        maxBytes = maxBytes,
        format = if (isTiny) ImageFormat.WEBP else type,
        // No floor for the tiny: its limit is a hard one, because the server rejects the upload over it.
        minQuality = if (isTiny) 1 else ImageOutput.DEFAULT_MIN_QUALITY,
        // A colour profile alone could use up most of the tiny's limit.
        keepColorProfile = !isTiny,
    )
}

private fun RenderedImage.toEmbedded() = EmbeddedThumb(width, height, "image/webp", Base64Bytes(bytes))

private fun RenderedImage.toThumbnailFile(payloadKey: String) = ThumbnailFile(
    pixelWidth = width,
    pixelHeight = height,
    thumbnailBytes = bytes,
    key = payloadKey,
    contentType = when (format) {
        ImageFormat.WEBP -> "image/webp"
        ImageFormat.JPEG -> "image/jpeg"
        ImageFormat.PNG -> "image/png"
        ImageFormat.BMP -> "image/bmp"
        ImageFormat.GIF -> "image/gif"
    },
    quality = quality,
)

/**
 * Attempts to extract dimensions from SVG data using basic string parsing
 */
private fun getSvgDimensions(svgData: ByteArray): ImageSize? {
    return try {
        val svgContent = svgData.decodeToString()

        // Look for width and height attributes in the SVG tag
        // Match numbers with optional px/pt/etc suffix
        val widthRegex = """width\s*=\s*["']?(\d+)(?:px)?""".toRegex()
        val heightRegex = """height\s*=\s*["']?(\d+)(?:px)?""".toRegex()

        val widthMatch = widthRegex.find(svgContent)
        val heightMatch = heightRegex.find(svgContent)

        if (widthMatch != null && heightMatch != null) {
            val width = widthMatch.groupValues[1].toIntOrNull()
            val height = heightMatch.groupValues[1].toIntOrNull()

            if (width != null && height != null) {
                return ImageSize(width, height)
            }
        }

        null
    } catch (_: Exception) {
        null
    }
}
