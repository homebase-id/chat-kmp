package id.homebase.api.image

import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.homebasekmppoc.prototype.lib.drives.upload.EmbeddedThumb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// Thumb presets
val baseThumbSizes = listOf(
    ThumbnailInstruction(quality = 84, maxPixelDimension = 320, maxBytes = 26 * 1024),
    ThumbnailInstruction(quality = 84, maxPixelDimension = 640, maxBytes = 102 * 1024),
    ThumbnailInstruction(quality = 76, maxPixelDimension = 1080, maxBytes = 291 * 1024),
    ThumbnailInstruction(quality = 76, maxPixelDimension = 1600, maxBytes = 640 * 1024)
)

val tinyThumbSize = ThumbnailInstruction(quality = 76, maxPixelDimension = 20, maxBytes = 768)

@OptIn(ExperimentalEncodingApi::class)
private fun toBase64(bytes: ByteArray): String = Base64.encode(bytes)

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
 * Main entry — mirrors createThumbnails in JS
 */
suspend fun createThumbnails(
    imageBytes: ByteArray,
    payloadKey: String,
    thumbSizes: List<ThumbnailInstruction>? = null
): Triple<ImageSize, EmbeddedThumb, List<ThumbnailFile>> = withContext(Dispatchers.Default) {

    // GIF and SVG handling: for SVG files we expect bytes to be svg xml; for GIFs we treat them specially
    // Check these FIRST before trying to decode, as Skia can't decode SVG
    val header = imageBytes.take(16).toByteArray().decodeToString().lowercase()
    val isSvg = header.contains("<svg") || header.contains("<?xml")
    val isGif = imageBytes.size >= 3 && imageBytes[0] == 0x47.toByte() /* G */ && imageBytes[1] == 0x49.toByte() /* I */ && imageBytes[2] == 0x46.toByte() /* F */

    if (isSvg) {
        // For SVG, we return the original vector format
        // Try to get dimensions from SVG, fallback to default
        val naturalSize = getSvgDimensions(imageBytes) ?: ImageSize(320, 320)

        val vectorThumb = ThumbnailFile(
            pixelWidth = naturalSize.pixelWidth,
            pixelHeight = naturalSize.pixelHeight,
            payload = imageBytes,
            key = payloadKey,
            contentType = "image/svg+xml",
            quality = 100
        )
        val embedded = EmbeddedThumb(
            pixelWidth = naturalSize.pixelWidth,
            pixelHeight = naturalSize.pixelHeight,
            contentType = "image/svg+xml",
            content = toBase64(vectorThumb.payload)
        )

        return@withContext Triple(naturalSize, embedded, listOf(vectorThumb))
    }

    // Determine natural size using ImageUtils (for non-SVG images)
    val naturalSize = ImageUtils.getNaturalSize(imageBytes)

    if (isGif) {
        // For GIF, create tiny thumb only (webp), no additional thumbnails
        val tinyThumbFile = createImageThumbnail(imageBytes, payloadKey, tinyThumbSize, isTinyThumb = true)
        val embeddedTiny = EmbeddedThumb(
            pixelWidth = naturalSize.pixelWidth,
            pixelHeight = naturalSize.pixelHeight,
            contentType = "image/webp",
            content = toBase64(tinyThumbFile.payload)
        )
        return@withContext Triple(naturalSize, embeddedTiny, emptyList())
    }

    // general image case
    val tinyThumbFile = createImageThumbnail(imageBytes, payloadKey, tinyThumbSize, isTinyThumb = true)

    val requestedSizes = thumbSizes ?: baseThumbSizes
    val applicableThumbs = getRevisedThumbs(naturalSize, requestedSizes)

    // Create additional thumbnails (NOT including tiny thumb - only the applicable thumbs)
    val additional = applicableThumbs.map { instr ->
        // create with no tiny flag
        createImageThumbnail(imageBytes, payloadKey, instr, isTinyThumb = false)
    }

    val embeddedTiny = EmbeddedThumb(
        pixelWidth = tinyThumbFile.pixelWidth,
        pixelHeight = tinyThumbFile.pixelHeight,
        contentType = "image/webp",
        content = toBase64(tinyThumbFile.payload)
    )

    return@withContext Triple(naturalSize, embeddedTiny, additional)
}

/**
 * Create a single thumbnail according to instruction.
 * - Resizes to fit inside maxPixelDimension x maxPixelDimension preserving aspect.
 * - Tries initial quality; if resulting size > maxBytes it reduces quality in a loop until satisfying or quality==1.
 * - Returns ThumbnailFile (payload bytes).
 */
suspend fun createImageThumbnail(
    imageBytes: ByteArray,
    payloadKey: String,
    instruction: ThumbnailInstruction,
    isTinyThumb: Boolean = false
): ThumbnailFile = withContext(Dispatchers.Default) {
    // Determine target format (tiny -> webp forced)
    val targetFormat = if (isTinyThumb) ImageFormat.WEBP else instruction.type

    val maxDim = instruction.maxPixelDimension

    var quality = instruction.quality.coerceIn(1, 100)
    var currentInputBytes = imageBytes

    // Check if image is already at perfect size and doesn't need processing
    val naturalSize = ImageUtils.getNaturalSize(imageBytes)
    val naturalMax = max(naturalSize.pixelWidth, naturalSize.pixelHeight)

    // If image is already at exact target size and within size limit, return as-is
    // This optimization avoids unnecessary re-encoding
    if (naturalMax == maxDim &&
        imageBytes.size <= instruction.maxBytes &&
        !isTinyThumb) {
        return@withContext ThumbnailFile(
            pixelWidth = naturalSize.pixelWidth,
            pixelHeight = naturalSize.pixelHeight,
            payload = imageBytes,
            key = payloadKey,
            contentType = "image/${targetFormat.name.lowercase()}",
            quality = quality
        )
    }

    // First resize attempt
    var result = ImageUtils.resizePreserveAspect(
        currentInputBytes,
        maxWidth = maxDim,
        maxHeight = maxDim,
        outputFormat = targetFormat,
        quality = quality
    )

    var safetyCounter = 0
    while (result.bytes.size > instruction.maxBytes && quality > 1 && safetyCounter < 20) {
        safetyCounter++

        // Adjust quality drop proportional to excess
        val excessRatio = result.bytes.size.toDouble() / instruction.maxBytes.toDouble()
        val qualityDrop = minOf(40, maxOf(5, (quality * excessRatio * 0.5).roundToInt()))
        quality = (quality - qualityDrop).coerceAtLeast(1)

        // Use previous result as input for next compression attempt
        currentInputBytes = result.bytes
        result = ImageUtils.compressOnly(
            currentInputBytes,
            outputFormat = targetFormat,
            quality = quality
        )

        // If still too big for tiny thumb and quality reached near 1, try shrinking pixel dimensions
        if (result.bytes.size > instruction.maxBytes && quality <= 2 && isTinyThumb) {
            val newDim = maxOf(1, maxDim - 5)
            if (newDim < 1) {
                throw IllegalStateException("Cannot shrink tiny thumb further")
            }

            // Try with smaller dimensions
            result = ImageUtils.resizePreserveAspect(
                imageBytes,
                maxWidth = newDim,
                maxHeight = newDim,
                outputFormat = targetFormat,
                quality = 2
            )
            break
        }
    }

    val finalBytes = result.bytes
    val thumb = ThumbnailFile(
        pixelWidth = result.size.pixelWidth,
        pixelHeight = result.size.pixelHeight,
        payload = finalBytes,
        key = payloadKey,
        contentType = when (targetFormat) {
            ImageFormat.WEBP -> "image/webp"
            ImageFormat.JPEG -> "image/jpeg"
            ImageFormat.PNG -> "image/png"
            ImageFormat.BMP -> "image/bmp"
            ImageFormat.GIF -> "image/gif"
        },
        quality = quality
    )

    return@withContext thumb
}

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
