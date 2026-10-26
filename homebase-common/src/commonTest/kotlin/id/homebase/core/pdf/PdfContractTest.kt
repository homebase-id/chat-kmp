package id.homebase.core.pdf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PdfContractTest {

    @Test
    fun emptyBytesGiveNoThumbnail() {
        assertNull(generatePdfThumbnail(ByteArray(0), 200))
    }

    @Test
    fun nonPdfBytesGiveNoThumbnail() {
        assertNull(generatePdfThumbnail("definitely not a pdf".encodeToByteArray(), 200))
    }

    @Test
    fun missingFileGivesNoThumbnail() {
        assertNull(generatePdfThumbnailFromFile("/no/such/dir/missing.pdf", 200))
    }

    @Test
    fun nonPositiveWidthGivesNoThumbnail() {
        assertNull(generatePdfThumbnail("%PDF-1.4".encodeToByteArray(), 0))
        assertNull(generatePdfThumbnailFromFile("/no/such/dir/missing.pdf", -1))
    }

    @Test
    fun rendererIsEmptyUntilOpened() {
        val renderer = PdfRenderer()
        assertEquals(0, renderer.pageCount)
        assertFailsWith<IllegalArgumentException> { renderer.renderPage(0, 100, 100) }
        renderer.close()
        renderer.close()
    }

    @Test
    fun openingAMissingFileThrowsAndLeavesNothingOpen() {
        val renderer = PdfRenderer()
        assertFailsWith<Throwable> { renderer.open("/no/such/dir/missing.pdf") }
        assertEquals(0, renderer.pageCount)
        renderer.close()
    }
}
