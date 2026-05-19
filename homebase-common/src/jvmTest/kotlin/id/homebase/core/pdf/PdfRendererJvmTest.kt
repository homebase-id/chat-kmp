package id.homebase.core.pdf

import id.homebase.api.HomebaseProtocol
import id.homebase.api.image.createImageThumbnail
import id.homebase.api.image.tinyThumbSize
import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfRendererJvmTest {

    private fun createTestPdf(pageCount: Int): File {
        val file = File.createTempFile("test_pdf_", ".pdf")
        PDDocument().use { doc ->
            repeat(pageCount) { i ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 24f)
                    cs.newLineAtOffset(100f, 700f)
                    cs.showText("Page ${i + 1}")
                    cs.endText()
                }
            }
            doc.save(file)
        }
        return file
    }

    @Test
    fun open_validPdf_reportsCorrectPageCount() {
        val file = createTestPdf(5)
        try {
            val renderer = PdfRenderer()
            renderer.open(file.absolutePath)
            assertEquals(5, renderer.pageCount)
            renderer.close()
        } finally {
            file.delete()
        }
    }

    @Test
    fun renderPage_returnsImageBitmapWithPositiveDimensions() {
        val file = createTestPdf(1)
        try {
            val renderer = PdfRenderer()
            renderer.open(file.absolutePath)
            val bitmap = renderer.renderPage(0, 640, 480)
            assertTrue(bitmap.width > 0)
            assertTrue(bitmap.height > 0)
            renderer.close()
        } finally {
            file.delete()
        }
    }

    @Test
    fun close_doubleCloseDoesNotThrow() {
        val file = createTestPdf(1)
        try {
            val renderer = PdfRenderer()
            renderer.open(file.absolutePath)
            renderer.close()
            renderer.close()
        } finally {
            file.delete()
        }
    }

    @Test
    fun renderPage_withoutOpen_throws() {
        val renderer = PdfRenderer()
        try {
            renderer.renderPage(0, 640, 480)
            assertTrue(false, "Expected exception")
        } catch (_: IllegalArgumentException) { }
        catch (_: IllegalStateException) { }
    }

    @Test
    fun open_invalidPath_throws() {
        val renderer = PdfRenderer()
        try {
            renderer.open("/nonexistent/path/fake.pdf")
            assertTrue(false, "Expected exception")
        } catch (_: Exception) { }
    }

    @Test
    fun renderPage_multiplePages_eachRendersIndependently() {
        val file = createTestPdf(3)
        try {
            val renderer = PdfRenderer()
            renderer.open(file.absolutePath)
            val bitmaps = (0 until 3).map { renderer.renderPage(it, 300, 400) }
            bitmaps.forEach { assertTrue(it.width > 0) }
            assertEquals(3, bitmaps.size)
            renderer.close()
        } finally {
            file.delete()
        }
    }
    @Test
    fun close_thenPageCount_returnsZero() {
        val file = createTestPdf(3)
        try {
            val renderer = PdfRenderer()
            renderer.open(file.absolutePath)
            assertEquals(3, renderer.pageCount)
            renderer.close()
            assertEquals(0, renderer.pageCount)
        } finally {
            file.delete()
        }
    }

    @Test
    fun reopen_afterClose_works() {
        val file1 = createTestPdf(2)
        val file2 = createTestPdf(4)
        try {
            val renderer = PdfRenderer()
            renderer.open(file1.absolutePath)
            assertEquals(2, renderer.pageCount)
            renderer.close()

            renderer.open(file2.absolutePath)
            assertEquals(4, renderer.pageCount)
            val bitmap = renderer.renderPage(0, 300, 400)
            assertTrue(bitmap.width > 0)
            renderer.close()
        } finally {
            file1.delete()
            file2.delete()
        }
    }
}

class PdfThumbnailGeneratorJvmTest {

    private fun createTestPdfBytes(pageCount: Int): ByteArray {
        val baos = java.io.ByteArrayOutputStream()
        PDDocument().use { doc ->
            repeat(pageCount) { i ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 24f)
                    cs.newLineAtOffset(100f, 700f)
                    cs.showText("Page ${i + 1}")
                    cs.endText()
                }
            }
            doc.save(baos)
        }
        return baos.toByteArray()
    }

    @Test
    fun generatePdfThumbnail_validPdf_returnsJpegBytesAndPageCount() {
        val pdfBytes = createTestPdfBytes(3)
        val result = generatePdfThumbnail(pdfBytes, 320)
        assertNotNull(result)
        assertEquals(3, result.pageCount)
        val thumb = result.thumbnailBytes
        assertNotNull(thumb)
        assertTrue(thumb.size > 100)
        assertEquals(0xFF.toByte(), thumb[0])
        assertEquals(0xD8.toByte(), thumb[1])
        assertEquals(0xFF.toByte(), thumb[2])
    }

    @Test
    fun generatePdfThumbnail_invalidBytes_returnsNull() {
        val garbage = "this is not a pdf".toByteArray()
        val result = generatePdfThumbnail(garbage, 320)
        assertNull(result)
    }

    @Test
    fun generatePdfThumbnail_singlePage_returnsPageCountOne() {
        val pdfBytes = createTestPdfBytes(1)
        val result = generatePdfThumbnail(pdfBytes, 320)
        assertNotNull(result)
        assertEquals(1, result.pageCount)
    }

    @Test
    fun generatePdfThumbnail_tinyThumb_fitsUnderMaxEmbeddedThumbBytes() = runTest {
        val pdfBytes = createTestPdfBytes(3)
        val pdfResult = generatePdfThumbnail(pdfBytes, 320)
        assertNotNull(pdfResult)
        val thumbBytes = pdfResult.thumbnailBytes
        assertNotNull(thumbBytes)

        val tinyThumb = createImageThumbnail(
            thumbBytes, "test_key", tinyThumbSize, isTinyThumb = true,
        )
        assertTrue(
            tinyThumb.thumbnailBytes.size <= HomebaseProtocol.MaxEmbeddedThumbBytes,
            "Tiny thumb ${tinyThumb.thumbnailBytes.size} bytes exceeds " +
                "${HomebaseProtocol.MaxEmbeddedThumbBytes} byte server limit",
        )
        assertTrue(tinyThumb.pixelWidth in 1..30)
        assertTrue(tinyThumb.pixelHeight in 1..30)
    }

    @Test
    fun generatePdfThumbnail_emptyByteArray_returnsNull() {
        val result = generatePdfThumbnail(ByteArray(0), 320)
        assertNull(result)
    }
}
