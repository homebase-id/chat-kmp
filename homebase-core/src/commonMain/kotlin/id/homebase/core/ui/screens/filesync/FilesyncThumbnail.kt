package id.homebase.core.ui.screens.filesync

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageHeaderParser
import id.homebase.api.image.convertHeicToJpeg
import id.homebase.api.image.createTinyPreview
import id.homebase.api.lib.image.ImageFormatDetector
import id.homebase.core.filesync.FilesyncProtocol
import id.homebase.core.pdf.PDF_CARD_RENDER_WIDTH
import id.homebase.core.pdf.generatePdfThumbnailFromFile
import id.homebase.core.util.FileKind
import id.homebase.core.util.fileKindOf
import kotlinx.coroutines.CancellationException

private const val TAG = "FilesyncThumbnail"
private const val MAX_SOURCE_BYTES = 64L * 1024 * 1024
private const val MAX_SOURCE_PIXELS = 40L * 1000 * 1000

/** The tiny preview an image or PDF goes up with, or null: a preview never decides whether a file uploads. */
internal suspend fun filesyncEmbeddedPreview(
    sourcePath: String,
    name: String,
    fileOperations: FileOperationsProvider,
): EmbeddedThumb? {
    val kind = fileKindOf(null, name)
    if (kind != FileKind.Image && kind != FileKind.Pdf) return null
    return try {
        if (fileOperations.getFileSize(sourcePath) > MAX_SOURCE_BYTES) return null
        val raster = if (kind == FileKind.Pdf) {
            generatePdfThumbnailFromFile(sourcePath, PDF_CARD_RENDER_WIDTH)?.thumbnailBytes
        } else {
            fileOperations.readFileBytes(sourcePath).let { bytes ->
                if (ImageFormatDetector.isHeic(bytes)) convertHeicToJpeg(bytes) else bytes
            }
        } ?: return null
        if (kind == FileKind.Image && !withinPixelBudget(raster)) return null
        createTinyPreview(raster, FilesyncProtocol.PayloadKey)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(e, TAG) { "no preview for $name" }
        null
    } catch (e: OutOfMemoryError) {
        Logger.w(e, TAG) { "no preview for $name: out of memory" }
        null
    }
}

// Header-only: getNaturalSize decodes the whole image on skia. An unparseable header gets no preview rather than a blind decode.
private fun withinPixelBudget(bytes: ByteArray): Boolean {
    val size = ImageHeaderParser.parse(bytes) ?: return false
    return size.pixelWidth.toLong() * size.pixelHeight <= MAX_SOURCE_PIXELS
}
