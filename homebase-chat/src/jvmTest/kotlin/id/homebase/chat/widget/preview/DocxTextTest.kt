package id.homebase.chat.widget.preview

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocxTextTest {

    private val xml = """<?xml version="1.0" encoding="UTF-8"?><w:document xmlns:w="x"><w:body>
        <w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Quarterly report</w:t></w:r></w:p>
        <w:p><w:r><w:t xml:space="preserve">Revenue is </w:t></w:r><w:r><w:rPr><w:b/></w:rPr><w:t>up</w:t></w:r><w:r><w:tab/><w:t>R&amp;D &#233; &lt;ok&gt;</w:t></w:r></w:p>
        <w:p></w:p><w:p><w:r><w:t>one</w:t><w:br/><w:t>two</w:t></w:r></w:p></w:body></w:document>"""

    private fun docx(name: String = "word/document.xml", content: ByteArray = xml.toByteArray()): String {
        val file = File.createTempFile("fixture", ".docx").also { it.deleteOnExit() }
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(content)
            zip.closeEntry()
        }
        return file.path
    }

    @Test
    fun paragraphsKeepTheTextAndDropTheFormatting() {
        assertEquals(
            listOf("Quarterly report", "Revenue is up\tR&D é <ok>", "", "one", "two"),
            docxParagraphs(xml),
        )
    }

    @Test
    fun theDocumentXmlIsReadFromTheArchive() {
        val text = readDocxDocumentXml(docx()).decodeToString()
        assertEquals(docxParagraphs(xml), docxParagraphs(text))
    }

    @Test
    fun anArchiveWithoutADocumentFails() {
        assertFailsWith<Exception> { readDocxDocumentXml(docx("other.xml")) }
    }

    @Test
    fun aFileThatIsNotAZipFails() {
        val file = File.createTempFile("broken", ".docx").also { it.deleteOnExit() }
        file.writeText("not a zip archive")
        assertFailsWith<Exception> { readDocxDocumentXml(file.path) }
    }

    @Test
    fun anOverCapDocumentIsReadOnlyToTheCap() {
        val big = ByteArray((DOCX_XML_LIMIT_BYTES + 1024).toInt()) { 'a'.code.toByte() }
        assertEquals(DOCX_XML_LIMIT_BYTES + 1, readDocxDocumentXml(docx(content = big)).size.toLong())
    }

    @Test
    fun aDocxMapsToItsPreviewOnlyWhereItCanBeRead() {
        val type = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        assertEquals(FilePreviewKind.Docx, filePreviewKind(type, "a.docx", 1_000, web = false, pdfAvailable = true, docxAvailable = true))
        assertEquals(null, filePreviewKind(type, "a.docx", 1_000, web = false, pdfAvailable = true, docxAvailable = false))
        assertEquals(null, filePreviewKind(null, "a.docx", 1_000, web = true, pdfAvailable = true, docxAvailable = false))
    }
}
