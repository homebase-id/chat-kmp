package id.homebase.chat.services.builder

import co.touchlab.kermit.Logger
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.MediaQuality
import id.homebase.api.image.createThumbnails
import id.homebase.api.image.standardThumbSizes
import id.homebase.core.pdf.generatePdfThumbnailFromFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object MessageThumbnailGenerator {

    private const val PDF_PREVIEW_RENDER_WIDTH = 1600

    /**
     * [mediaQuality] defaults to HIGH so the non-photo callers (audio waveform, PDF page) keep the
     * full ladder; only user photos opt into the shorter STANDARD one.
     */
    suspend fun generate(
        filePath: String,
        payloadKey: String,
        fileOperationsProvider: FileOperationsProvider,
        mediaQuality: MediaQuality = MediaQuality.HIGH,
    ): ThumbnailResult {
        val bytes = fileOperationsProvider.readFileBytes(filePath)

        val thumbSizes =
            if (mediaQuality == MediaQuality.STANDARD) standardThumbSizes else null
        val (_, tinyThumb, thumbnails) = createThumbnails(bytes, payloadKey, thumbSizes)

        return ThumbnailResult(
            preview = tinyThumb,
            thumbnails = thumbnails,
            sourceBytes = bytes
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
            sourceBytes = pageJpeg
        )
    }
}


