package id.homebase.core.pdf

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import android.graphics.pdf.PdfRenderer as AndroidPdfRenderer

actual class PdfRenderer actual constructor() {

    private var fileDescriptor: ParcelFileDescriptor? = null
    private var nativeRenderer: AndroidPdfRenderer? = null

    actual fun open(filePath: String) {
        val fd = ParcelFileDescriptor.open(File(filePath), ParcelFileDescriptor.MODE_READ_ONLY)
        fileDescriptor = fd
        nativeRenderer = AndroidPdfRenderer(fd)
    }

    actual val pageCount: Int
        get() = nativeRenderer?.pageCount ?: 0

    actual fun renderPage(index: Int, width: Int, height: Int): ImageBitmap {
        val renderer = requireNotNull(nativeRenderer) { "PdfRenderer not opened. Call open() first." }
        val page = renderer.openPage(index)
        val scaleX = width.toFloat() / page.width
        val scaleY = height.toFloat() / page.height
        val scale = minOf(scaleX, scaleY)
        val bitmapWidth = (page.width * scale).toInt()
        val bitmapHeight = (page.height * scale).toInt()
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        page.render(bitmap, null, null, AndroidPdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        return bitmap.asImageBitmap()
    }

    actual fun close() {
        try { nativeRenderer?.close() } catch (_: Exception) { }
        try { fileDescriptor?.close() } catch (_: Exception) { }
        nativeRenderer = null
        fileDescriptor = null
    }
}
