package id.homebase.core.ui.screens.filesync

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.upload.EmbeddedThumb
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.createImageThumbnail
import id.homebase.api.image.tinyThumbSize
import id.homebase.api.serialization.Base64Bytes
import id.homebase.core.filesync.FilesyncProtocol
import id.homebase.core.pdf.generatePdfThumbnailFromFile
import id.homebase.core.util.FileKind
import id.homebase.core.util.fileKindOf
import kotlinx.coroutines.CancellationException

private const val TAG = "FilesyncThumbnail"
private const val PDF_RENDER_WIDTH = 320
private const val MAX_SOURCE_BYTES = 64L * 1024 * 1024

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
            generatePdfThumbnailFromFile(sourcePath, PDF_RENDER_WIDTH)?.thumbnailBytes
        } else {
            fileOperations.readFileBytes(sourcePath)
        } ?: return null
        val tiny = createImageThumbnail(raster, FilesyncProtocol.PayloadKey, tinyThumbSize, isTinyThumb = true)
        EmbeddedThumb(tiny.pixelWidth, tiny.pixelHeight, tiny.contentType, Base64Bytes(tiny.thumbnailBytes))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(e, TAG) { "no preview for $name" }
        null
    }
}
