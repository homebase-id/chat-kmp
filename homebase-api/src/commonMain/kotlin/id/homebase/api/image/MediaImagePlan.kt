package id.homebase.api.image

import kotlin.math.max
import kotlin.math.min

data class PlannedImage(val width: Int, val height: Int, val quality: Int) {
    val longEdge get() = max(width, height)
}

/**
 * What to make for one source image. [finalImage] is the payload, null where the file itself is.
 * The table in [ROWS] is the only source of sizes and qualities for media.
 */
data class MediaImagePlan(
    val tiny: PlannedImage,
    val thumbnails: List<PlannedImage>,
    val finalImage: PlannedImage?,
)

/** The server rejects a thumbnail over 1 MiB, and encryption adds up to a block. */
const val MAX_THUMBNAIL_BYTES = 1024 * 1024 - 32

const val MAX_TINY_BYTES = 768

private class Row(
    val longEdge: Int,
    val minShortEdge: Int,
    val longestEdgeAllowed: Int,
    val standard: Int?,
    val hd: Int?,
    val original: Int?,
) {
    fun quality(setting: MediaQuality) = when (setting) {
        MediaQuality.STANDARD -> standard
        MediaQuality.HD -> hd
        MediaQuality.ORIGINAL -> original
    }
}

// The last filled row in a column is that setting's final image. Original's final image is the file.
private val ROWS = listOf(
    Row(320, 240, 640, standard = 70, hd = 80, original = 80),
    Row(640, 480, 1280, standard = 70, hd = 80, original = 80),
    Row(1080, 810, 2160, standard = 70, hd = 76, original = 80),
    Row(2048, 1080, 8192, standard = 60, hd = 70, original = 76),
    Row(4096, 2160, 8192, standard = null, hd = 80, original = null),
)

/** What a vector has to render at to cover this setting's thumbnails. */
internal fun largestThumbnailLongEdge(setting: MediaQuality): Int {
    val rows = ROWS.filter { it.quality(setting) != null }
    return (if (setting == MediaQuality.ORIGINAL) rows else rows.dropLast(1)).last().longEdge
}

/** Pure integer math on the displayed size: nothing here decodes, scales or encodes. */
fun planMediaImages(source: ImageSize, setting: MediaQuality): MediaImagePlan {
    val rows = ROWS.mapNotNull { row -> row.quality(setting)?.let { row to it } }
    val finalRow = if (setting == MediaQuality.ORIGINAL) null else rows.last()
    val thumbnailRows = if (finalRow == null) rows else rows.dropLast(1)

    val thumbnails = mergeNearSizes(
        thumbnailRows.map { (row, quality) -> sized(source, row.longEdge, row.minShortEdge, row.longestEdgeAllowed, quality) },
    )
    val finalImage = finalRow?.let { (row, quality) ->
        val image = sized(source, row.longEdge, row.minShortEdge, row.longestEdgeAllowed, quality)
        val largest = thumbnails.lastOrNull()
        // Otherwise the sharpest thing a viewer can load would look worse than the thumbnail before it.
        if (largest != null && isNearSize(largest.longEdge, image.longEdge)) {
            image.copy(quality = max(image.quality, largest.quality))
        } else {
            image
        }
    }
    return MediaImagePlan(
        tiny = sized(source, tinyThumbSize.maxPixelDimension, 0, tinyThumbSize.maxPixelDimension, tinyThumbSize.quality),
        thumbnails = thumbnails,
        finalImage = finalImage,
    )
}

private fun sized(source: ImageSize, rowLong: Int, rowMinShort: Int, rowLimit: Int, quality: Int): PlannedImage {
    val long = max(source.pixelWidth, source.pixelHeight).toLong()
    val short = min(source.pixelWidth, source.pixelHeight).toLong()
    var outLong = rowLong.toLong()
    if (short * rowLong < rowMinShort * long) {
        outLong = (rowMinShort * long + short - 1) / short
    }
    outLong = min(min(outLong, rowLimit.toLong()), long)
    val outShort = max(1L, (short * outLong * 2 + long) / (long * 2))
    return if (source.pixelWidth >= source.pixelHeight) {
        PlannedImage(outLong.toInt(), outShort.toInt(), quality)
    } else {
        PlannedImage(outShort.toInt(), outLong.toInt(), quality)
    }
}

private fun mergeNearSizes(smallestFirst: List<PlannedImage>): List<PlannedImage> {
    val kept = mutableListOf<PlannedImage>()
    for (image in smallestFirst) {
        val previous = kept.lastOrNull()
        if (previous != null && isNearSize(previous.longEdge, image.longEdge)) {
            kept[kept.lastIndex] = image.copy(quality = max(previous.quality, image.quality))
        } else {
            kept += image
        }
    }
    return kept
}

private fun isNearSize(smaller: Int, larger: Int) = smaller.toLong() * 10 >= larger.toLong() * 9
