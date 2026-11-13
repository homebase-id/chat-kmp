package id.homebase.upload

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageMetadataScrubber
import id.homebase.api.image.ImageSize
import id.homebase.api.image.MediaQuality
import id.homebase.api.image.RenderedImage
import id.homebase.api.image.createMediaImages
import id.homebase.api.image.createThumbnails
import id.homebase.api.lib.image.ImageFormatDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "UploadImages"

data class ThumbnailResult(
    val preview: EmbeddedThumb?,
    val thumbnails: List<ThumbnailFile>,
    val sourceFormat: String?,
    val sourceByteCount: Long?,
    /** What a Standard or HD send should carry instead of the file, made from the same decode. */
    val finalImage: RenderedImage? = null,
    val displayedSize: ImageSize? = null,
)

object UploadImages {

    /**
     * [mediaQuality] defaults to ORIGINAL so the non-photo callers (audio waveform, PDF page) get
     * thumbnails only. A source that can't be decoded still sends, with no thumbnails.
     *
     * [forceSticker] sources keep their file as the final image: a lossy re-encode would chew on a
     * sticker's alpha.
     */
    suspend fun generate(
        filePath: String,
        payloadKey: String,
        fileOperationsProvider: FileOperationsProvider,
        mediaQuality: MediaQuality = MediaQuality.ORIGINAL,
        forceSticker: Boolean = false,
    ): ThumbnailResult {
        val media = try {
            createMediaImages(filePath, fileOperationsProvider, payloadKey, mediaQuality, allowFinalImage = !forceSticker)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "No thumbnails for $payloadKey; sending the file as it is" }
            return ThumbnailResult(preview = null, thumbnails = emptyList(), sourceFormat = null, sourceByteCount = null)
        }
        return ThumbnailResult(
            preview = media.tiny,
            thumbnails = media.thumbnails,
            sourceFormat = media.sourceFormat,
            sourceByteCount = media.sourceByteCount,
            finalImage = media.finalImage,
            displayedSize = media.displayedSize,
        )
    }

    /**
     * The first page of a PDF through the image thumbnail pipeline, so the receiver sees a preview
     * without downloading the PDF. Null, for the icon row, when [render] can't render it.
     */
    suspend fun fromPdf(filePath: String, payloadKey: String, render: PdfPageRenderer): ThumbnailResult? {
        val pageJpeg = withContext(Dispatchers.Default) {
            try {
                render(filePath)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(tag = TAG, throwable = e) { "PDF preview render failed" }
                null
            }
        } ?: return null

        val (_, tinyThumb, thumbnails) = createThumbnails(pageJpeg, payloadKey)
        return ThumbnailResult(
            preview = tinyThumb,
            thumbnails = thumbnails,
            sourceFormat = ImageFormatDetector.detectFormat(pageJpeg),
            sourceByteCount = pageJpeg.size.toLong(),
        )
    }

    /**
     * The final image a Standard or HD send makes, written to a file; null to send the source itself, which is
     * also chosen when the source already fits and re-encoding wouldn't save a quarter. It replaces the
     * file rather than joining the thumbnails: the server drops thumbnails whose payload sent no bytes.
     */
    suspend fun finalImagePath(thumbs: ThumbnailResult, fileOperationsProvider: FileOperationsProvider): String? {
        val image = thumbs.finalImage ?: return null
        val sourceFits = thumbs.displayedSize?.let { it.pixelWidth == image.width && it.pixelHeight == image.height } == true
        val savesEnough = thumbs.sourceByteCount?.let { image.bytes.size.toLong() * 4 <= it * 3 } ?: true
        val keepable = thumbs.sourceFormat in ImageFormatDetector.TYPES_EVERY_CLIENT_CAN_DECODE
        if (sourceFits && !savesEnough && keepable) return null

        return try {
            fileOperationsProvider.writeBytesToTempFile(image.bytes, "img_final_", ".webp")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "Couldn't save the final image; sending the file as it is" }
            null
        }
    }
}

/**
 * A path to the image at [path] with its EXIF/XMP removed, or [path] itself when there was nothing to
 * strip. The pixel data stays bit-identical, unlike a re-encode.
 *
 * Fail-soft: a file that can't be read, isn't a format the scrubber handles, or can't be parsed goes out
 * as it is rather than failing the send. That costs a leak, not a crash, so it is logged loudly.
 */
internal suspend fun withoutImageMetadata(path: String, contentType: String, fileOps: FileOperationsProvider): String {
    if (!contentType.startsWith("image/")) return path
    val original = try {
        fileOps.readFileBytes(path)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Logger.w(throwable = t, tag = TAG) { "metadata scrub skipped — could not read $path" }
        return path
    }
    val why = when (val outcome = ImageMetadataScrubber.scrubWithOutcome(original)) {
        is ImageMetadataScrubber.Outcome.Scrubbed -> return fileOps.writeBytesToTempFile(outcome.bytes, "scrubbed_", ".img")
        ImageMetadataScrubber.Outcome.NothingToStrip -> return path
        ImageMetadataScrubber.Outcome.UnsupportedFormat -> "isn't a format the scrubber handles"
        ImageMetadataScrubber.Outcome.Unparseable -> "couldn't be parsed"
    }
    Logger.w(tag = TAG) {
        "metadata scrub skipped — $contentType (sniffed ${ImageFormatDetector.detectFormat(original)}) $why; any EXIF/XMP goes out intact"
    }
    return path
}
