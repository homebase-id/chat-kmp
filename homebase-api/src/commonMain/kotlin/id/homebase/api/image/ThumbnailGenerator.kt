package id.homebase.api.image

import id.homebase.api.serialization.Base64Bytes
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.lib.image.ImageFormatDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

val tinyThumbSize = ThumbnailInstruction(quality = 76, maxPixelDimension = 20, maxBytes = MAX_TINY_BYTES)

/**
 * Everything made from one image in one decode. [finalImage] is the payload, null where the file itself is.
 * [sourceFormat] and [sourceByteCount] describe the file, so a caller needn't read it again to ask.
 */
class MediaImages(
    val displayedSize: ImageSize,
    val tiny: EmbeddedThumb?,
    val thumbnails: List<ThumbnailFile>,
    val finalImage: RenderedImage?,
    val sourceFormat: String? = null,
    val sourceByteCount: Long? = null,
    /** Only read when asked for. */
    val metadata: ImageMetadata? = null,
)

private fun isSvg(bytes: ByteArray): Boolean {
    val header = bytes.take(16).toByteArray().decodeToString().lowercase()
    return header.contains("<svg") || header.contains("<?xml")
}

/**
 * The tiny preview, thumbnails and, when [allowFinalImage] says so, the final image for [setting].
 * A GIF gets only a tiny, because its animated file is the payload. An SVG can't produce a final image.
 *
 * Throws on a raster image the platform can't decode; only a bad SVG returns empty, because
 * receivers gate the image block on a `hasImage` flag and no thumb is better than a stuck outbox.
 */
suspend fun createMediaImages(
    imageBytes: ByteArray,
    payloadKey: String,
    setting: MediaQuality = MediaQuality.ORIGINAL,
    allowFinalImage: Boolean = true,
    readMetadata: Boolean = false,
): MediaImages = withContext(Dispatchers.Default) {
    val media = if (isSvg(imageBytes)) {
        createSvgImages(imageBytes, payloadKey, setting)
    } else {
        openImageSource(imageBytes).use { mediaImagesFrom(it, imageBytes, payloadKey, setting, allowFinalImage, readMetadata) }
    }
    media.withSource(ImageFormatDetector.detectFormat(imageBytes), imageBytes.size.toLong())
}

/**
 * [createMediaImages] for the file at [path]. On the phones the decoder reads the file itself, so it
 * never sits in memory whole; elsewhere, and for a Photos-library asset or an SVG, the bytes are read once.
 */
suspend fun createMediaImages(
    path: String,
    fileOps: FileOperationsProvider,
    payloadKey: String,
    setting: MediaQuality = MediaQuality.ORIGINAL,
    allowFinalImage: Boolean = true,
    readMetadata: Boolean = false,
): MediaImages = withContext(Dispatchers.Default) {
    val file = openImageFile(path)
        ?: return@withContext createMediaImages(fileOps.readFileBytes(path), payloadKey, setting, allowFinalImage, readMetadata)
    file.use {
        val header = fileOps.readFileHeaderBytes(path)
        if (isSvg(header)) {
            return@withContext createMediaImages(fileOps.readFileBytes(path), payloadKey, setting, allowFinalImage, readMetadata)
        }
        mediaImagesFrom(it, header, payloadKey, setting, allowFinalImage, readMetadata)
            .withSource(ImageFormatDetector.detectFormat(header), fileOps.getFileSize(path))
    }
}

// Every check here needs only the header: a GIF keeps its file whether or not it animates.
private suspend fun mediaImagesFrom(
    source: ImageSource,
    header: ByteArray,
    payloadKey: String,
    setting: MediaQuality,
    allowFinalImage: Boolean,
    readMetadata: Boolean,
): MediaImages {
    val gif = ImageFormatDetector.detectFormat(header) == "image/gif"
    val keepsItsFile = gif || !allowFinalImage || ImageFormatDetector.isAnimated(header)
    val media = renderPlanned(source, payloadKey, displayedSize = null) { size ->
        val plan = planMediaImages(size, setting)
        plan.copy(thumbnails = if (gif) emptyList() else plan.thumbnails, finalImage = if (keepsItsFile) null else plan.finalImage)
    }
    val metadata = if (readMetadata) source.metadata() else null
    if (!gif) return MediaImages(media.displayedSize, media.tiny, media.thumbnails, media.finalImage, metadata = metadata)
    // Receivers lay a GIF out from the tiny's size, so it reports the natural one.
    val natural = media.displayedSize
    return MediaImages(natural, media.tiny?.copy(pixelWidth = natural.pixelWidth, pixelHeight = natural.pixelHeight), media.thumbnails, null, metadata = metadata)
}

private fun MediaImages.withSource(format: String?, byteCount: Long) =
    MediaImages(displayedSize, tiny, thumbnails, finalImage, format, byteCount, metadata)

// Vector data is resolution-independent, so a 16x16 favicon still gets its full set of thumbnails:
// it's planned from a render at the largest row rather than from its declared size.
private suspend fun createSvgImages(bytes: ByteArray, payloadKey: String, setting: MediaQuality): MediaImages {
    val natural = getSvgDimensions(bytes) ?: ImageSize(320, 320)
    return try {
        val raster = ImageUtils.rasterizeSvg(bytes, largestThumbnailLongEdge(setting), ImageFormat.PNG, 100)
        openImageSource(raster.bytes).use { source ->
            renderPlanned(source, payloadKey, natural) { planMediaImages(it, setting).copy(finalImage = null) }
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        MediaImages(natural, null, emptyList(), null)
    }
}

private suspend fun renderPlanned(
    source: ImageSource,
    payloadKey: String,
    displayedSize: ImageSize?,
    planFor: (ImageSize) -> MediaImagePlan,
): MediaImages {
    var plan: MediaImagePlan? = null
    val context = currentCoroutineContext()
    val result = renderFrom(source, { context.ensureActive() }) { size -> planFor(size).also { plan = it }.toOutputs() }
    val thumbnailCount = plan!!.thumbnails.size
    return MediaImages(
        displayedSize = displayedSize ?: result.displayedSize,
        tiny = result.images.last().toEmbedded(),
        thumbnails = result.images.take(thumbnailCount).map { it.toThumbnailFile(payloadKey) },
        finalImage = result.images[thumbnailCount].takeIf { plan!!.finalImage != null },
    )
}

// The plan's order, which renderPlanned reads back by position: thumbnails, final image, tiny.
private fun MediaImagePlan.toOutputs(): List<ImageOutput> = buildList {
    thumbnails.forEach {
        add(ImageOutput(it.width, it.height, it.quality, MAX_THUMBNAIL_BYTES, overLimit = OverLimit.SHRINK, rowSize = it.rowSize))
    }
    finalImage?.let { add(ImageOutput(it.width, it.height, it.quality, Int.MAX_VALUE)) }
    add(tinyOutput(tiny.width, tiny.height))
}

// No quality floor: the server rejects the whole upload over the limit, so the limit wins. And no
// colour profile, because one alone could use up most of the limit.
private fun tinyOutput(width: Int, height: Int) = ImageOutput(
    width,
    height,
    tinyThumbSize.quality,
    tinyThumbSize.maxBytes,
    minQuality = 1,
    keepColorProfile = false,
)

/** Thumbnails only, as Original's rows: for images that aren't the payload, or whose payload stays as it is. */
suspend fun createThumbnails(imageBytes: ByteArray, payloadKey: String): Triple<ImageSize, EmbeddedThumb?, List<ThumbnailFile>> =
    createMediaImages(imageBytes, payloadKey).let { Triple(it.displayedSize, it.tiny, it.thumbnails) }

/** Only the file's embedded preview, sized as the tiny itself. Throws if it can't be made. */
suspend fun createTinyPreview(imageBytes: ByteArray): EmbeddedThumb = withContext(Dispatchers.Default) {
    val source = if (isSvg(imageBytes)) {
        ImageUtils.rasterizeSvg(imageBytes, tinyThumbSize.maxPixelDimension, ImageFormat.PNG, 100).bytes
    } else {
        imageBytes
    }
    renderImages(source) { size ->
        val tiny = planMediaImages(size, MediaQuality.ORIGINAL).tiny
        listOf(tinyOutput(tiny.width, tiny.height))
    }.images.single().toEmbedded()
}

/**
 * One image outside the media table, fitting inside maxPixelDimension on both axes and within
 * maxBytes: profile avatars, which keep their own sizes.
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
    return if (isTiny) tinyOutput(width, height) else ImageOutput(width, height, quality, maxBytes, type)
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
