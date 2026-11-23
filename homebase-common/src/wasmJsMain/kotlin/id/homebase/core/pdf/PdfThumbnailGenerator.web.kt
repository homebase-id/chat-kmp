package id.homebase.core.pdf

import id.homebase.api.file.systemFileSystem
import id.homebase.api.util.jsUint8ArrayToByteArray
import id.homebase.api.util.toJsUint8Array
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import okio.Path.Companion.toPath
import kotlin.js.Promise

actual suspend fun generatePdfThumbnail(bytes: ByteArray, maxWidth: Int): PdfThumbnailResult? {
    if (maxWidth <= 0 || bytes.isEmpty()) return null
    return try {
        val page = renderFirstPage(bytes.toJsUint8Array(), maxWidth).await<JsAny?>() ?: return null
        PdfThumbnailResult(jsUint8ArrayToByteArray(pageJpeg(page)), pageCount(page))
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }
}

actual suspend fun generatePdfThumbnailFromFile(filePath: String, maxWidth: Int): PdfThumbnailResult? {
    val bytes = try {
        systemFileSystem.read(filePath.toPath()) { readByteArray() }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        return null
    }
    return generatePdfThumbnail(bytes, maxWidth)
}

private fun renderFirstPage(bytes: JsAny, maxWidth: Int): Promise<JsAny?> = js("globalThis.__odinPdf.renderFirstPage(bytes, maxWidth)")
private fun pageJpeg(page: JsAny): JsAny = js("page.jpeg")
private fun pageCount(page: JsAny): Int = js("page.pageCount")
