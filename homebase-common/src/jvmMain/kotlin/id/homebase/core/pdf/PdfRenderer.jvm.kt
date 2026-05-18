package id.homebase.core.pdf

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import java.io.File
import org.apache.pdfbox.rendering.PDFRenderer as PdfBoxRenderer

actual class PdfRenderer actual constructor() {

    private var document: PDDocument? = null
    private var boxRenderer: PdfBoxRenderer? = null

    actual fun open(filePath: String) {
        val doc = Loader.loadPDF(File(filePath))
        document = doc
        boxRenderer = PdfBoxRenderer(doc)
    }

    actual val pageCount: Int
        get() = document?.numberOfPages ?: 0

    actual fun renderPage(index: Int, width: Int, height: Int): ImageBitmap {
        val renderer = requireNotNull(boxRenderer) { "PdfRenderer not opened. Call open() first." }
        val page = requireNotNull(document).getPage(index)
        val mediaBox = page.mediaBox
        val scaleX = width.toFloat() / mediaBox.width
        val scaleY = height.toFloat() / mediaBox.height
        val scale = minOf(scaleX, scaleY)
        val dpi = scale * 72f
        val bufferedImage = renderer.renderImageWithDPI(index, dpi, ImageType.RGB)
        return bufferedImage.toComposeImageBitmap()
    }

    actual fun close() {
        try {
            document?.close()
        } catch (_: Exception) { }
        document = null
        boxRenderer = null
    }
}
