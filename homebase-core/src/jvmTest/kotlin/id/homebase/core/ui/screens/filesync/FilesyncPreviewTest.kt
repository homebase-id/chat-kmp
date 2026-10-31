package id.homebase.core.ui.screens.filesync

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun aTextFileShowsItsTextAndItsCopyGoesWhenItCloses() = runComposeUiTest {
        val file = File.createTempFile("preview", ".txt").apply { deleteOnExit(); writeText("hello from filesync") }
        var open by mutableStateOf(true)
        var gone: String? = null
        setContent {
            MaterialTheme {
                if (open) FilesyncPreview(FilesyncPreviewFile(file.path, "notes.txt"), onClose = { open = false }, onGone = { gone = it })
            }
        }
        onNodeWithText("hello from filesync").assertExists()
        onNodeWithText("notes.txt").assertExists()
        assertNull(gone)
        onNodeWithContentDescription("Close").performClick()
        waitForIdle()
        assertEquals(file.path, gone)
    }

    @Test
    fun aLongTextFileShowsItsStartAndSaysSo() = runComposeUiTest {
        val file = File.createTempFile("preview", ".txt").apply { deleteOnExit(); writeText((1..40_000).joinToString("\n") { "line $it" }) }
        setContent { MaterialTheme { FilesyncPreview(FilesyncPreviewFile(file.path, "big.txt"), onClose = {}, onGone = {}) } }
        onNodeWithText("Showing the first", substring = true).assertExists()
        onNodeWithText("line 1").assertExists()
    }
}
