package id.homebase.core.pdf

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.File
import android.graphics.pdf.PdfRenderer as AndroidPdfRenderer

actual fun generatePdfThumbnail(bytes: ByteArray, maxWidth: Int): PdfThumbnailResult? {
    return try {
        val tempFile = File.createTempFile("pdf_thumb_", ".pdf")
        try {
            tempFile.writeBytes(bytes)
            val fd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                val renderer = AndroidPdfRenderer(fd)
                try {
                    val page = renderer.openPage(0)
                    val scale = maxWidth.toFloat() / page.width
                    val bitmapWidth = (page.width * scale).toInt()
                    val bitmapHeight = (page.height * scale).toInt()
                    val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
                    try {
                        page.render(bitmap, null, null, AndroidPdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    } finally {
                        page.close()
                    }
                    val pageCount = renderer.pageCount
                    val output = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output)
                    PdfThumbnailResult(
                        thumbnailBytes = output.toByteArray(),
                        pageCount = pageCount,
                    )
                } finally {
                    renderer.close()
                }
            } finally {
                fd.close()
            }
        } finally {
            tempFile.delete()
        }
    } catch (_: Exception) {
        null
    }
}
