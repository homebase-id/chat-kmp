package id.homebase.api.image

import kotlin.math.max
import kotlin.math.min

/** [rowSize] is what the row asked for, set only when the 10% rule enlarged this to the source's size. */
data class PlannedImage(val width: Int, val height: Int, val quality: Int, val rowSize: ImageSize? = null) {
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

/** Pure integer math on the displayed size. [makesFinalImage] is false where the file stays the payload. */
fun planMediaImages(source: ImageSize, setting: MediaQuality, makesFinalImage: Boolean = true): MediaImagePlan {
    val rows = ROWS.mapNotNull { row -> row.quality(setting)?.let { row to it } }
    val thumbnailRows = if (setting == MediaQuality.ORIGINAL) rows else rows.dropLast(1)
    val finalRow = if (setting == MediaQuality.ORIGINAL || !makesFinalImage) null else rows.last()

    val thumbnails = mergeNearSizes(
        thumbnailRows.map { (row, quality) -> sized(source, row.longEdge, row.minShortEdge, row.longestEdgeAllowed, quality) },
    )
    val finalImage = finalRow?.let { (row, quality) ->
        val image = sized(source, row.longEdge, row.minShortEdge, row.longestEdgeAllowed, quality).copy(rowSize = null)
        val largest = thumbnails.lastOrNull()
        // A small source's final image is encoded no harsher than the row it stands in for.
        if (largest != null && isNearSize(largest.longEdge, image.longEdge)) {
            image.copy(quality = max(image.quality, largest.quality))
        } else {
            image
        }
    }
    return MediaImagePlan(
        tiny = sized(source, tinyThumbSize.maxPixelDimension, 0, tinyThumbSize.maxPixelDimension, tinyThumbSize.quality, keepSourceWhenNear = false),
        thumbnails = withoutCloseSteps(thumbnails, finalImage),
        finalImage = finalImage,
    )
}

// An untouched file's bytes don't follow its pixels, so without a final image the largest thumbnail stays.
private fun withoutCloseSteps(smallestFirst: List<PlannedImage>, finalImage: PlannedImage?) =
    smallestFirst.foldRightIndexed(emptyList<PlannedImage>()) { index, image, kept ->
        val above = kept.firstOrNull() ?: finalImage
        if (index == 0 || above == null || isAStepBelow(image.longEdge, above.longEdge)) listOf(image) + kept else kept
    }

private fun sized(
    source: ImageSize,
    rowLong: Int,
    rowMinShort: Int,
    rowLimit: Int,
    quality: Int,
    keepSourceWhenNear: Boolean = true,
): PlannedImage {
    val long = max(source.pixelWidth, source.pixelHeight).toLong()
    val short = min(source.pixelWidth, source.pixelHeight).toLong()
    var outLong = rowLong.toLong()
    if (short * rowLong < rowMinShort * long) {
        outLong = (rowMinShort * long + short - 1) / short
    }
    outLong = min(min(outLong, rowLimit.toLong()), long)
    val oriented = { edge: Long ->
        val outShort = max(1L, (short * edge * 2 + long) / (long * 2))
        if (source.pixelWidth >= source.pixelHeight) ImageSize(edge.toInt(), outShort.toInt()) else ImageSize(outShort.toInt(), edge.toInt())
    }
    // A 2-10% reduction resamples every pixel, softening the image, to save few of them.
    val enlarged = keepSourceWhenNear && outLong < long && outLong * 10 >= long * 9
    val size = oriented(if (enlarged) long else outLong)
    return PlannedImage(size.pixelWidth, size.pixelHeight, quality, rowSize = if (enlarged) oriented(outLong) else null)
}

private fun mergeNearSizes(smallestFirst: List<PlannedImage>): List<PlannedImage> {
    val kept = mutableListOf<PlannedImage>()
    for (image in smallestFirst) {
        val previous = kept.lastOrNull()
        if (previous != null && isNearSize(previous.longEdge, image.longEdge)) {
            kept[kept.lastIndex] = image.copy(quality = max(previous.quality, image.quality), rowSize = image.rowSize ?: previous.rowSize)
        } else {
            kept += image
        }
    }
    return kept
}

private fun isNearSize(smaller: Int, larger: Int) = smaller.toLong() * 10 >= larger.toLong() * 9

// 1.5× on the edge: below that, a thumbnail costs about as much to send as it saves a viewer.
private fun isAStepBelow(smaller: Int, larger: Int) = larger.toLong() * 2 >= smaller.toLong() * 3
