package id.homebase.chat.services.builder

import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.MediaQuality
import id.homebase.api.image.createMediaImages
import id.homebase.api.image.createThumbnails
import id.homebase.api.lib.image.ImageFormatDetector
import id.homebase.core.pdf.generatePdfThumbnailFromFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MessageThumbnailGenerator {

    private const val PDF_PREVIEW_RENDER_WIDTH = 1600

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
            Logger.w(tag = "MessageThumbnailGenerator", throwable = e) { "No thumbnails for $payloadKey; sending the file as it is" }
            val format = ImageFormatDetector.detectFormat(fileOperationsProvider.readFileHeaderBytes(filePath))
            return ThumbnailResult(preview = null, thumbnails = emptyList(), sourceFormat = format, sourceByteCount = null)
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
     * Renders the first page of a PDF and runs it through the same image
     * thumbnail pipeline, so the receiver sees a preview without downloading
     * the full PDF. Returns null (caller falls back to a plain, preview-less
     * payload → the icon row) when the renderer is unavailable (web) or the
     * PDF can't be rendered.
     */
    suspend fun generateFromPdf(filePath: String, payloadKey: String): ThumbnailResult? {
        val pageJpeg = withContext(Dispatchers.Default) {
            try {
                generatePdfThumbnailFromFile(filePath, PDF_PREVIEW_RENDER_WIDTH)?.thumbnailBytes
            } catch (e: Exception) {
                Logger.w(tag = "MessageThumbnailGenerator", throwable = e) { "PDF preview render failed" }
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
}


