package id.homebase.core.ui.screens.openedfile

import id.homebase.chat.widget.preview.FilePreviewKind
import id.homebase.chat.widget.preview.filePreviewKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OpenedFileKindTest {

    private fun kind(mime: String?, name: String) = filePreviewKind(mime, name, sizeBytes = 1_000)

    @Test
    fun everyTypeTheEntryPointsOfferMapsToItsRenderer() {
        assertEquals(FilePreviewKind.Pdf, kind("application/pdf", "a.pdf"))
        assertEquals(FilePreviewKind.Text, kind("text/plain", "a.txt"))
        assertEquals(FilePreviewKind.Text, kind("text/csv", "a.csv"))
        assertEquals(FilePreviewKind.Text, kind("application/json", "a.json"))
        assertEquals(FilePreviewKind.Markdown, kind("text/markdown", "a.md"))
    }

    @Test
    fun theTypeFromTheOtherAppWinsOverAMissingOrWrongExtension() {
        assertEquals(FilePreviewKind.Pdf, kind("application/pdf", "attachment"))
        assertEquals(FilePreviewKind.Text, kind("text/plain", "attachment.bin"))
        assertEquals(FilePreviewKind.Markdown, kind("text/markdown", "README"))
        assertEquals(FilePreviewKind.Pdf, kind(null, "scan.PDF"))
        assertEquals(FilePreviewKind.Text, kind("application/octet-stream", "notes.txt"))
    }

    @Test
    fun officeAndUnknownFilesGetTheNoPreviewCard() {
        listOf(
            "application/msword" to "a.doc",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "a.xlsx",
            "application/vnd.ms-excel" to "a.xls",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "a.pptx",
            "application/vnd.ms-powerpoint" to "a.ppt",
            "application/vnd.oasis.opendocument.text" to "a.odt",
            "application/vnd.oasis.opendocument.spreadsheet" to "a.ods",
            "application/rtf" to "a.rtf",
            "application/x-unknown" to "mystery",
            null to "noextension",
        ).forEach { (mime, name) -> assertNull(kind(mime, name), "$mime $name") }
    }
}
