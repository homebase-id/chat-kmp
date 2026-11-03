package id.homebase.core.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PdfContractJvmTest {

    private fun pdfBytes(pages: Int, userPassword: String? = null): ByteArray {
        val out = ByteArrayOutputStream()
        PDDocument().use { doc ->
            repeat(pages) { doc.addPage(PDPage()) }
            if (userPassword != null) {
                val policy = StandardProtectionPolicy("owner-secret", userPassword, AccessPermission())
                policy.encryptionKeyLength = 128
                doc.protect(policy)
            }
            doc.save(out)
        }
        return out.toByteArray()
    }

    private inline fun withFile(bytes: ByteArray, block: (File) -> Unit) {
        val file = File.createTempFile("pdf_contract_", ".pdf")
        try {
            file.writeBytes(bytes)
            block(file)
        } finally {
            file.delete()
        }
    }

    @Test
    fun thumbnailIsAJpegOfTheRequestedWidthWithTheDocumentPageCount() {
        val result = assertNotNull(generatePdfThumbnail(pdfBytes(3), 200))
        assertEquals(3, result.pageCount)
        val image = assertNotNull(ImageIO.read(ByteArrayInputStream(assertNotNull(result.thumbnailBytes))))
        assertTrue(abs(image.width - 200) <= 1, "width ${image.width}")
        assertEquals(792.0 / 612.0, image.height.toDouble() / image.width, 0.02)
    }

    @Test
    fun fileAndBytesGiveTheSameResult() {
        val bytes = pdfBytes(2)
        withFile(bytes) { file ->
            val fromFile = assertNotNull(generatePdfThumbnailFromFile(file.absolutePath, 150))
            val fromBytes = assertNotNull(generatePdfThumbnail(bytes, 150))
            assertEquals(fromBytes.pageCount, fromFile.pageCount)
        }
    }

    @Test
    fun nonPositiveWidthOfAValidPdfGivesNoThumbnail() {
        val bytes = pdfBytes(1)
        assertNull(generatePdfThumbnail(bytes, 0))
        withFile(bytes) { file -> assertNull(generatePdfThumbnailFromFile(file.absolutePath, -1)) }
    }

    @Test
    fun passwordProtectedPdfGivesNoThumbnailAndCannotBeOpened() {
        val bytes = pdfBytes(1, userPassword = "secret")
        assertNull(generatePdfThumbnail(bytes, 200))
        withFile(bytes) { file ->
            assertNull(generatePdfThumbnailFromFile(file.absolutePath, 200))
            val renderer = PdfRenderer()
            assertFailsWith<Throwable> { renderer.open(file.absolutePath) }
            assertEquals(0, renderer.pageCount)
        }
    }

    @Test
    fun renderedPageFitsInsideTheRequestedBox() {
        withFile(pdfBytes(2)) { file ->
            val renderer = PdfRenderer()
            renderer.open(file.absolutePath)
            assertEquals(2, renderer.pageCount)
            val bitmap = renderer.renderPage(1, 400, 400)
            assertTrue(bitmap.width <= 401 && bitmap.height <= 401, "${bitmap.width}x${bitmap.height}")
            assertTrue(bitmap.width < bitmap.height)
            renderer.close()
        }
    }

    @Test
    fun outOfRangePageThrows() {
        withFile(pdfBytes(1)) { file ->
            val renderer = PdfRenderer()
            renderer.open(file.absolutePath)
            assertFailsWith<Throwable> { renderer.renderPage(5, 100, 100) }
            renderer.close()
        }
    }

    @Test
    fun reopeningReplacesTheDocument() {
        withFile(pdfBytes(1)) { one ->
            withFile(pdfBytes(4)) { four ->
                val renderer = PdfRenderer()
                renderer.open(one.absolutePath)
                renderer.open(four.absolutePath)
                assertEquals(4, renderer.pageCount)
                renderer.close()
                assertEquals(0, renderer.pageCount)
            }
        }
    }
}
