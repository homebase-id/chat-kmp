package id.homebase.core.ui.screens.filesync

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.material3.MaterialTheme
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FilesyncPreviewTest {

    @Test
    fun aQuickLookTakesWhatEveryPlatformCanShow() {
        assertEquals(FilesyncPreviewKind.Image, filesyncPreviewKind("Beach.JPG"))
        assertEquals(FilesyncPreviewKind.Video, filesyncPreviewKind("clip.mov"))
        assertEquals(FilesyncPreviewKind.Pdf, filesyncPreviewKind("report.pdf"), "the desktop renders PDFs")
        assertEquals(FilesyncPreviewKind.Text, filesyncPreviewKind("notes.md"))
        assertEquals(FilesyncPreviewKind.Text, filesyncPreviewKind("data.json"))
        listOf("photo.heic", "clip.webm", "budget.xlsx", "archive.zip", "noextension").forEach { assertNull(filesyncPreviewKind(it), it) }
    }

    @Test
    fun aTextFileShowsItsTextAndCloses() = runComposeUiTest {
        val file = File.createTempFile("preview", ".txt").apply { deleteOnExit(); writeText("hello from filesync") }
        var closed = false
        setContent { MaterialTheme { FilesyncPreview(FilesyncPreviewFile(file.path, "notes.txt", FilesyncPreviewKind.Text)) { closed = true } } }
        onNodeWithText("hello from filesync").assertExists()
        onNodeWithText("notes.txt").assertExists()
        onNodeWithContentDescription("Close").performClick()
        assertTrue(closed)
    }

    @Test
    fun aLongTextFileShowsItsStartAndSaysSo() = runComposeUiTest {
        val file = File.createTempFile("preview", ".txt").apply { deleteOnExit(); writeText((1..40_000).joinToString("\n") { "line $it" }) }
        setContent { MaterialTheme { FilesyncPreview(FilesyncPreviewFile(file.path, "big.txt", FilesyncPreviewKind.Text)) {} } }
        onNodeWithText("Showing the first", substring = true).assertExists()
        onNodeWithText("line 1").assertExists()
    }
}
