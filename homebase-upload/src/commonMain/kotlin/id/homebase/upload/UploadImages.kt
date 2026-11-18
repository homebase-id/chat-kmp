package id.homebase.upload

import id.homebase.api.image.withSourceMetadata
import id.homebase.api.file.contentTypeFromBytes
import id.homebase.api.file.extensionForContentType
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

// Wide enough that the ladder's larger rows exist (THUMBNAILS.md: a PDF preview gets Original's rows).
private const val PDF_PAGE_RENDER_WIDTH = 1600

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
            createMediaImages(filePath, fileOperationsProvider, payloadKey, mediaQuality, allowFinalImage = !forceSticker, hdr = true)
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
                render(filePath, PDF_PAGE_RENDER_WIDTH)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(tag = TAG, throwable = e) { "PDF preview render failed" }
                null
            }
        } ?: return null

        val (_, tinyThumb, thumbnails) = try {
            createThumbnails(pageJpeg, payloadKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "PDF preview thumbnails failed; sending the file as it is" }
            return null
        }
        return ThumbnailResult(
            preview = tinyThumb,
            thumbnails = thumbnails,
            sourceFormat = contentTypeFromBytes(pageJpeg),
            sourceByteCount = pageJpeg.size.toLong(),
        )
    }

    /**
     * The final image a Standard or HD send makes, written to a file; null to send the source itself, which is
     * also chosen when the source already fits and re-encoding wouldn't save a quarter. It replaces the
     * file rather than joining the thumbnails: the server drops thumbnails whose payload sent no bytes.
     * [keepMetadataFrom]: the source whose metadata the final image carries, for an app that keeps it.
     */
    suspend fun finalImagePath(
        thumbs: ThumbnailResult,
        fileOperationsProvider: FileOperationsProvider,
        keepMetadataFrom: String? = null,
    ): String? {
        val image = thumbs.finalImage ?: return null
        val sourceFits = thumbs.displayedSize?.let { it.pixelWidth == image.width && it.pixelHeight == image.height } == true
        val savesEnough = thumbs.sourceByteCount?.let { image.bytes.size.toLong() * 4 <= it * 3 } ?: true
        val keepable = thumbs.sourceFormat in ImageFormatDetector.TYPES_EVERY_CLIENT_CAN_DECODE
        if (sourceFits && !savesEnough && keepable) return null

        val bytes = keepMetadataFrom?.takeIf { thumbs.sourceFormat in ImageMetadataScrubber.BLOCK_FORMATS }
            ?.let { withMetadataOf(it, image, fileOperationsProvider) } ?: image.bytes
        return try {
            fileOperationsProvider.writeBytesToTempFile(bytes, "img_final_", ".${extensionForContentType(image.format.contentType)}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "Couldn't save the final image; sending the file as it is" }
            null
        }
    }
}

// Fail-soft like the strip: a source that can't be read leaves the final image without metadata, loudly.
private suspend fun withMetadataOf(source: String, image: RenderedImage, fileOps: FileOperationsProvider): ByteArray? = try {
    val original = fileOps.readFileBytes(source)
    withContext(Dispatchers.Default) { withSourceMetadata(image.bytes, image.format, original, image.width, image.height) }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Logger.w(tag = TAG, throwable = e) { "Couldn't copy $source's metadata into its final image; it goes without" }
    null
}
