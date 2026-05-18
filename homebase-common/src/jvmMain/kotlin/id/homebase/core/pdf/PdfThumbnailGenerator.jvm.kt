package id.homebase.core.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

actual fun generatePdfThumbnail(bytes: ByteArray, maxWidth: Int): PdfThumbnailResult? {
    return try {
        Loader.loadPDF(bytes).use { document ->
            val pageCount = document.numberOfPages
            val renderer = PDFRenderer(document)
            val page = document.getPage(0)
            val scale = maxWidth.toFloat() / page.mediaBox.width
            val dpi = scale * 72f
            val image = renderer.renderImageWithDPI(0, dpi, ImageType.RGB)
            val output = ByteArrayOutputStream()
            ImageIO.write(image, "JPEG", output)
            PdfThumbnailResult(
                thumbnailBytes = output.toByteArray(),
                pageCount = pageCount,
            )
        }
    } catch (_: Exception) {
        null
    }
}
