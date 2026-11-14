package id.homebase.upload

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.PayloadFile
import id.homebase.api.client.drives.files.ThumbnailFile
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageMetadata
import id.homebase.api.image.ImageMetadataScrubber
import id.homebase.api.image.ImageSize
import id.homebase.api.image.MediaQuality
import id.homebase.api.image.RenderedImage
import id.homebase.api.image.createMediaImages
import id.homebase.api.image.createThumbnails
import id.homebase.api.image.readImageMetadata
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
    /** Read only when asked for. */
    val metadata: ImageMetadata? = null,
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
        readMetadata: Boolean = false,
    ): ThumbnailResult {
        val media = try {
            createMediaImages(filePath, fileOperationsProvider, payloadKey, mediaQuality, allowFinalImage = !forceSticker, readMetadata = readMetadata)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "No thumbnails for $payloadKey; sending the file as it is" }
            // A format the decoder can't draw can still have EXIF the reader understands.
            val metadata = if (readMetadata) metadataOrNull(filePath, fileOperationsProvider) else null
            return ThumbnailResult(preview = null, thumbnails = emptyList(), sourceFormat = null, sourceByteCount = null, metadata = metadata)
        }
        return ThumbnailResult(
            preview = media.tiny,
            thumbnails = media.thumbnails,
            sourceFormat = media.sourceFormat,
            sourceByteCount = media.sourceByteCount,
            finalImage = media.finalImage,
            displayedSize = media.displayedSize,
            metadata = media.metadata,
        )
    }

    private suspend fun metadataOrNull(path: String, fileOps: FileOperationsProvider): ImageMetadata? = try {
        readImageMetadata(path, fileOps)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "No readable EXIF" }
        null
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
 * [payload] with its image's EXIF/XMP removed when its app strips. [staged]: the copy goes to outbox staging,
 * for a public upload the outbox sends from this path later; otherwise to upload-temp, which every start clears,
 * for the encryption step, which deletes it once encrypted.
 */
suspend fun withMetadataStripped(payload: PayloadFile, fileOps: FileOperationsProvider, staged: Boolean): PayloadFile {
    if (!payload.stripMetadata) return payload
    val stripped = imageWithoutMetadata(payload.filePath, payload.contentType, fileOps) ?: return payload
    val path = if (staged) {
        fileOps.writeBytesToOutboxTempFile(stripped, "scrubbed_", ".img")
    } else {
        fileOps.writeBytesToTempFile(stripped, "scrubbed_", ".img")
    }
    return payload.copy(filePath = path)
}

/**
 * [bytes] as an app's [policy] says to send them, for a sender that holds the image in memory (contact and
 * profile photos). Stripped even when the bytes are a fresh re-encode, so a later change of encoder can't
 * bring the metadata back.
 */
suspend fun imageBytesFor(policy: MetadataPolicy, bytes: ByteArray, contentType: String): ByteArray =
    if (policy == MetadataPolicy.STRIP) bytesWithoutMetadata(bytes, contentType) ?: bytes else bytes

/**
 * The image at [path] with its EXIF/XMP removed, or null when there was nothing to strip. The pixel data
 * stays bit-identical, unlike a re-encode.
 *
 * Fail-soft: a file that can't be read, isn't a format the scrubber handles, or can't be parsed goes out
 * as it is rather than failing the send. That costs a leak, not a crash, so it is logged loudly.
 */
private suspend fun imageWithoutMetadata(path: String, contentType: String, fileOps: FileOperationsProvider): ByteArray? {
    if (!contentType.startsWith("image/")) return null
    val original = try {
        fileOps.readFileBytes(path)
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Logger.w(throwable = t, tag = TAG) { "metadata scrub skipped — could not read $path" }
        return null
    }
    return bytesWithoutMetadata(original, contentType)
}

private suspend fun bytesWithoutMetadata(original: ByteArray, contentType: String): ByteArray? {
    if (!contentType.startsWith("image/")) return null
    val why = when (val outcome = withContext(Dispatchers.Default) { ImageMetadataScrubber.scrubWithOutcome(original) }) {
        is ImageMetadataScrubber.Outcome.Scrubbed -> return outcome.bytes
        ImageMetadataScrubber.Outcome.NothingToStrip -> return null
        ImageMetadataScrubber.Outcome.UnsupportedFormat -> "isn't a format the scrubber handles"
        ImageMetadataScrubber.Outcome.Unparseable -> "couldn't be parsed"
    }
    Logger.w(tag = TAG) {
        "metadata scrub skipped — $contentType (sniffed ${ImageFormatDetector.detectFormat(original)}) $why; any EXIF/XMP goes out intact"
    }
    return null
}
