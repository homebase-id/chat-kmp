package id.homebase.core.util

import kotlin.test.Test
import kotlin.test.assertTrue

class FileKindTest {

    private data class Row(val contentType: String?, val fileName: String?, val expected: FileKind)

    private val rows = listOf(
        Row("application/pdf", "report", FileKind.Pdf),
        Row("application/vnd.android.package-archive", null, FileKind.Apk),
        Row("application/x-7z-compressed", null, FileKind.Archive),
        Row("application/msword", null, FileKind.Word),
        Row("text/rtf", null, FileKind.Word),
        Row("application/vnd.openxmlformats-officedocument.wordprocessingml.document", null, FileKind.Word),
        Row("text/csv", null, FileKind.Spreadsheet),
        Row("application/vnd.ms-excel", null, FileKind.Spreadsheet),
        Row("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", null, FileKind.Spreadsheet),
        Row("application/vnd.oasis.opendocument.presentation", null, FileKind.Presentation),
        Row("application/vnd.ms-powerpoint", null, FileKind.Presentation),
        Row(
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            null,
            FileKind.Presentation,
        ),
        Row("text/plain", null, FileKind.Text),
        Row("text/x-markdown", null, FileKind.Text),
        Row("application/json", null, FileKind.Code),
        Row("text/html", null, FileKind.Code),
        Row("text/x-python", null, FileKind.Code),
        Row("application/ld+json", null, FileKind.Code),
        Row("application/atom+xml", null, FileKind.Code),
        Row("audio/mpeg", null, FileKind.Audio),
        Row("video/quicktime", null, FileKind.Video),
        Row("image/svg+xml", null, FileKind.Image),
        Row("Application/PDF", null, FileKind.Pdf),
        Row("text/plain; charset=utf-8", null, FileKind.Text),
        Row("application/octet-stream", "homebase-cards.zip", FileKind.Archive),
        Row("application/octet-stream", "deck.pptx", FileKind.Presentation),
        Row("application/octet-stream", "index.html", FileKind.Code),
        Row("application/octet-stream", "notes.md", FileKind.Text),
        Row(null, "report.pdf", FileKind.Pdf),
        Row("", "data.csv", FileKind.Spreadsheet),
        Row("  ", "backup.tar.gz", FileKind.Archive),
        Row("application/x-compressed", "old.zip", FileKind.Archive),
        Row("application/pdf", "misnamed.zip", FileKind.Pdf),
        Row("application/octet-stream", "PHOTOS.ZIP", FileKind.Archive),
        Row(null, "Letter.DOCX", FileKind.Word),
        Row("application/x-unknown", "blob.bin", FileKind.Generic),
        Row("application/octet-stream", "README", FileKind.Generic),
        Row("application/octet-stream", "archive.", FileKind.Generic),
        Row(null, null, FileKind.Generic),
    )

    @Test
    fun classifiesByMimeThenFileName() {
        val failures = rows.mapNotNull { (contentType, fileName, expected) ->
            val actual = fileKindOf(contentType, fileName)
            "fileKindOf($contentType, $fileName) = $actual, expected $expected".takeIf { actual != expected }
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }
}
