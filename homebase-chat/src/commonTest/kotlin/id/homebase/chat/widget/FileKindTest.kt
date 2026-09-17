package id.homebase.chat.widget

import kotlin.test.Test
import kotlin.test.assertEquals

class FileKindTest {

    @Test
    fun exactMime() {
        assertEquals(FileKind.Pdf, fileKindOf("application/pdf", "report"))
        assertEquals(FileKind.Archive, fileKindOf("application/zip", "a"))
        assertEquals(FileKind.Apk, fileKindOf("application/vnd.android.package-archive", null))
        assertEquals(FileKind.Code, fileKindOf("application/json", null))
    }

    @Test
    fun archiveMimeVariants() {
        listOf(
            "application/x-zip-compressed",
            "application/x-7z-compressed",
            "application/x-rar",
            "application/x-rar-compressed",
            "application/vnd.rar",
            "application/gzip",
            "application/x-gzip",
            "application/x-tar",
            "application/x-bzip2",
        ).forEach { assertEquals(FileKind.Archive, fileKindOf(it, null), it) }
    }

    @Test
    fun officeFamilies() {
        assertEquals(FileKind.Word, fileKindOf("application/msword", null))
        assertEquals(
            FileKind.Word,
            fileKindOf("application/vnd.openxmlformats-officedocument.wordprocessingml.document", null),
        )
        assertEquals(FileKind.Word, fileKindOf("application/rtf", null))
        assertEquals(FileKind.Word, fileKindOf("text/rtf", null))
        assertEquals(FileKind.Word, fileKindOf("application/vnd.oasis.opendocument.text", null))

        assertEquals(FileKind.Spreadsheet, fileKindOf("application/vnd.ms-excel", null))
        assertEquals(
            FileKind.Spreadsheet,
            fileKindOf("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", null),
        )
        assertEquals(FileKind.Spreadsheet, fileKindOf("text/csv", null))
        assertEquals(FileKind.Spreadsheet, fileKindOf("application/vnd.oasis.opendocument.spreadsheet", null))

        assertEquals(FileKind.Presentation, fileKindOf("application/vnd.ms-powerpoint", null))
        assertEquals(
            FileKind.Presentation,
            fileKindOf("application/vnd.openxmlformats-officedocument.presentationml.presentation", null),
        )
        assertEquals(FileKind.Presentation, fileKindOf("application/vnd.oasis.opendocument.presentation", null))
    }

    @Test
    fun textAndCode() {
        assertEquals(FileKind.Text, fileKindOf("text/plain", null))
        assertEquals(FileKind.Text, fileKindOf("text/markdown", null))
        assertEquals(FileKind.Text, fileKindOf("text/x-markdown", null))
        assertEquals(FileKind.Code, fileKindOf("application/javascript", null))
        assertEquals(FileKind.Code, fileKindOf("application/xml", null))
        assertEquals(FileKind.Code, fileKindOf("application/x-yaml", null))
        assertEquals(FileKind.Code, fileKindOf("text/x-python", null))
        assertEquals(FileKind.Code, fileKindOf("application/ld+json", null))
    }

    @Test
    fun mediaFamilies() {
        assertEquals(FileKind.Audio, fileKindOf("audio/mpeg", null))
        assertEquals(FileKind.Video, fileKindOf("video/quicktime", null))
        assertEquals(FileKind.Image, fileKindOf("image/svg+xml", null))
    }

    @Test
    fun mimeIsCaseAndParameterInsensitive() {
        assertEquals(FileKind.Pdf, fileKindOf("Application/PDF", null))
        assertEquals(FileKind.Text, fileKindOf("text/plain; charset=utf-8", null))
    }

    @Test
    fun octetStreamFallsBackToExtension() {
        assertEquals(FileKind.Archive, fileKindOf("application/octet-stream", "homebase-cards.zip"))
        assertEquals(FileKind.Presentation, fileKindOf("application/octet-stream", "deck.pptx"))
    }

    @Test
    fun missingMimeFallsBackToExtension() {
        assertEquals(FileKind.Pdf, fileKindOf(null, "report.pdf"))
        assertEquals(FileKind.Spreadsheet, fileKindOf("", "data.csv"))
        assertEquals(FileKind.Archive, fileKindOf("  ", "backup.tar.gz"))
    }

    @Test
    fun unrecognisedMimeFallsBackToExtension() {
        assertEquals(FileKind.Archive, fileKindOf("application/x-compressed", "old.zip"))
    }

    @Test
    fun specificMimeWinsOverExtension() {
        assertEquals(FileKind.Pdf, fileKindOf("application/pdf", "misnamed.zip"))
    }

    @Test
    fun uppercaseExtension() {
        assertEquals(FileKind.Archive, fileKindOf("application/octet-stream", "PHOTOS.ZIP"))
        assertEquals(FileKind.Word, fileKindOf(null, "Letter.DOCX"))
    }

    @Test
    fun unknownFallsBackToGeneric() {
        assertEquals(FileKind.Generic, fileKindOf("application/x-unknown", "blob.bin"))
        assertEquals(FileKind.Generic, fileKindOf("application/octet-stream", "README"))
        assertEquals(FileKind.Generic, fileKindOf("application/octet-stream", "archive."))
        assertEquals(FileKind.Generic, fileKindOf(null, null))
    }
}
