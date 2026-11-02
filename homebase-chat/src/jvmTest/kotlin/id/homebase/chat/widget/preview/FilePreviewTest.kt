package id.homebase.chat.widget.preview

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import java.io.File
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class FilePreviewTest {

    private fun kind(contentType: String?, name: String, size: Long? = 1_000, web: Boolean = false, pdf: Boolean = true) =
        filePreviewKind(contentType, name, size, web, pdf)

    @Test
    fun aPreviewTakesWhatEveryPlatformCanShow() {
        assertEquals(FilePreviewKind.Image, kind(null, "Beach.JPG"))
        assertEquals(FilePreviewKind.Video, kind(null, "clip.mov"))
        assertEquals(FilePreviewKind.Pdf, kind(null, "report.pdf"))
        assertEquals(FilePreviewKind.Markdown, kind(null, "notes.md"))
        assertEquals(FilePreviewKind.Text, kind(null, "data.json"))
        listOf("photo.heic", "clip.webm", "budget.xlsx", "archive.zip", "noextension").forEach { assertNull(kind(null, it), it) }
    }

    @Test
    fun markdownIsDecidedByTypeOrExtensionAndTheOtherTextStaysText() {
        assertEquals(FilePreviewKind.Markdown, kind("text/markdown", "download"))
        assertEquals(FilePreviewKind.Markdown, kind("text/x-markdown", "download"))
        assertEquals(FilePreviewKind.Markdown, kind("application/octet-stream", "README.markdown"))
        assertEquals(FilePreviewKind.Text, kind("text/plain", "notes.md"), "a declared plain type wins over the extension")
        assertEquals(FilePreviewKind.Text, kind("text/csv", "a.csv"))
        assertEquals(FilePreviewKind.Text, kind("application/json", "a.json"))
    }

    @Test
    fun wordAndOfficeFilesHaveNoPreview() {
        listOf(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "a.docx",
            "application/msword" to "a.doc",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "a.xlsx",
            "application/vnd.ms-powerpoint" to "a.ppt",
            "application/vnd.oasis.opendocument.text" to "a.odt",
            "application/rtf" to "a.rtf",
        ).forEach { (type, name) -> assertNull(kind(type, name), name) }
    }

    @Test
    fun onlyCsvAndJsonAreShownInMonospace() {
        assertTrue(isMonospacePreview("a.csv", "text/csv"))
        assertTrue(isMonospacePreview("a.json", null))
        assertTrue(isMonospacePreview("download", "application/json"))
        assertFalse(isMonospacePreview("a.txt", "text/plain"))
    }

    @Test
    fun markdownIsCutIntoBlocksAtBlankLinesButNeverInsideAFence() {
        val text = "# Title\n\npara one\nstill one\n\n```\ncode\n\nmore code\n```\n\n\nlast"
        assertEquals(
            listOf("# Title", "para one\nstill one", "```\ncode\n\nmore code\n```", "last"),
            splitMarkdownBlocks(text),
        )
        assertEquals(emptyList(), splitMarkdownBlocks("\n\n  \n"))
        assertEquals(listOf("only"), splitMarkdownBlocks("only"))
        assertEquals(listOf("a\nb"), splitMarkdownBlocks("a\r\nb"))
    }

    @Test
    fun aChatChipStillPreviewsMarkdown() {
        assertEquals(FilePreviewKind.Markdown, chatChipPreviewKind("text/markdown", "a.md", 10, web = false, pdfAvailable = true))
    }

    @Test
    fun theContentTypeWinsOverTheExtension() {
        assertEquals(FilePreviewKind.Pdf, kind("application/pdf", "download"))
        assertEquals(FilePreviewKind.Text, kind("text/plain; charset=utf-8", "notes.bin"))
        assertEquals(FilePreviewKind.Text, kind("application/xml", "feed"))
        assertNull(kind("application/zip", "notes.txt"))
        assertEquals(FilePreviewKind.Text, kind("application/octet-stream", "notes.txt"), "an unspecific type defers to the extension")
        assertNull(kind("text/vcard", "card.vcf"), "a contact is not a document")
    }

    @Test
    fun aVideoOverTheLimitOrOfUnknownSizeGetsNoPreview() {
        assertEquals(FilePreviewKind.Video, kind("video/mp4", "a.mp4", VIDEO_PREVIEW_LIMIT_BYTES))
        assertNull(kind("video/mp4", "a.mp4", VIDEO_PREVIEW_LIMIT_BYTES + 1))
        assertNull(kind("video/mp4", "a.mp4", null))
        assertEquals(FilePreviewKind.Text, kind("text/plain", "a.txt", null), "only a video needs its size")
    }

    @Test
    fun theWebTakesNothingItCannotHoldInMemory() {
        val big = id.homebase.api.file.WEB_DOWNLOAD_LIMIT_BYTES + 1
        assertNull(kind("application/pdf", "a.pdf", big, web = true))
        assertEquals(FilePreviewKind.Pdf, kind("application/pdf", "a.pdf", big, web = false))
        assertNull(kind("application/pdf", "a.pdf", 10, pdf = false))
    }

    @Test
    fun aChatChipPreviewsOnlyWhatChatDoesNotShowInline() {
        fun chip(type: String, name: String, size: Long? = 1_000, web: Boolean = false, pdf: Boolean = true) = chatChipPreviewKind(type, name, size, web, pdf)
        assertEquals(FilePreviewKind.Pdf, chip("application/pdf", "a.pdf"))
        assertEquals(FilePreviewKind.Text, chip("text/plain", "a.txt"))
        assertNull(chip("application/zip", "a.zip"))
        assertNull(chip("image/png", "a.png"))
        assertNull(chip("video/mp4", "a.mp4"))
        assertNull(chip("application/pdf", "a.pdf", web = true, pdf = false))
        assertNull(chip("text/plain", "a.txt", size = id.homebase.api.file.WEB_DOWNLOAD_LIMIT_BYTES + 1, web = true))
    }

    @Test
    fun aDecryptedCopyIsNamedByItsPayloadNotBySender() {
        val id = Uuid.random()
        val a = decryptedCopyName(id, "chat_web0", "../report #1.pdf", "application/pdf")
        val b = decryptedCopyName(Uuid.random(), "chat_web0", "../report #1.pdf", "application/pdf")
        assertTrue(a.endsWith(".pdf") && a.startsWith("preview_"), a)
        assertFalse(a.contains('/') || a.contains('#') || a.contains('%') || a.contains(' '), a)
        assertTrue(a != b, "two reports with one name must not share a copy")
        assertTrue(decryptedCopyName(id, "k", "x.p%df", "text/plain").endsWith(".txt"))
    }

    @Test
    fun aLongLineIsCutWithoutSplittingASurrogatePair() {
        val line = "a" + "😀".repeat(3_000)
        val pieces = splitLongLines(listOf(line, "short"), maxLength = 2_000)
        assertEquals(line, pieces.dropLast(1).joinToString(""))
        assertEquals("short", pieces.last())
        assertTrue(pieces.size > 2)
        pieces.forEach { piece ->
            assertTrue(piece.length <= 2_000)
            assertFalse(piece.first().isLowSurrogate(), "starts mid-pair")
            assertFalse(piece.last().isHighSurrogate(), "ends mid-pair")
        }
    }

    @Test
    fun aTextFileShowsItsTextAndItsCopyGoesWhenItCloses() = runComposeUiTest {
        val file = File.createTempFile("preview", ".txt").apply { deleteOnExit(); writeText("hello from preview") }
        var open by mutableStateOf(true)
        var gone: String? = null
        setContent {
            MaterialTheme {
                if (open) FilePreviewDialog(file.path, "notes.txt", FilePreviewKind.Text, onClose = { open = false }, onGone = { gone = it })
            }
        }
        onNodeWithText("hello from preview").assertExists()
        onNodeWithText("notes.txt").assertExists()
        assertNull(gone)
        onNodeWithContentDescription("Close").performClick()
        waitForIdle()
        assertEquals(file.path, gone)
    }

    @Test
    fun aLongTextFileShowsItsStartAndSaysSo() = runComposeUiTest {
        val file = File.createTempFile("preview", ".txt").apply { deleteOnExit(); writeText((1..40_000).joinToString("\n") { "line $it" }) }
        setContent { MaterialTheme { FilePreviewDialog(file.path, "big.txt", FilePreviewKind.Text, onClose = {}, onGone = {}) } }
        onNodeWithText("Showing the first", substring = true).assertExists()
        onNodeWithText("line 1").assertExists()
    }

    @Test
    fun aFetchedFileShowsItsContentAndIsDiscardedWhenItLeaves() = runComposeUiTest {
        val file = File.createTempFile("streamed", ".txt").apply { deleteOnExit(); writeText("fetched text") }
        var shown by mutableStateOf(true)
        val discarded = mutableListOf<String>()
        setContent {
            MaterialTheme {
                if (shown) StreamedFilePreview("k", "a.txt", FilePreviewKind.Text, load = { file.path }, discard = { discarded += it })
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("fetched text").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(discarded.isEmpty())
        shown = false
        waitForIdle()
        assertEquals(listOf(file.path), discarded)
    }

    @Test
    fun aFileThatCannotBeFetchedSaysSo() = runComposeUiTest {
        val discarded = mutableListOf<String>()
        setContent {
            MaterialTheme { StreamedFilePreview("k", "a.txt", FilePreviewKind.Text, load = { null }, discard = { discarded += it }) }
        }
        onNodeWithText("This file can't be shown here.").assertExists()
        assertTrue(discarded.isEmpty())
    }

    @Test
    fun aFetchedTextFileStartsBelowTheContentPadding() = runComposeUiTest {
        val file = File.createTempFile("streamed", ".txt").apply { deleteOnExit(); writeText("padded text") }
        setContent {
            MaterialTheme {
                StreamedFilePreview(
                    "k", "a.txt", FilePreviewKind.Text, load = { file.path }, discard = {},
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 100.dp),
                )
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("padded text").fetchSemanticsNodes().isNotEmpty() }
        val top = onNodeWithText("padded text").fetchSemanticsNode().boundsInRoot.top
        assertTrue(top >= 100f, "text top $top")
    }
}
