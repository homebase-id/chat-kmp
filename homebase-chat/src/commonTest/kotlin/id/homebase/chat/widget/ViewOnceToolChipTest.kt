package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.chat.conversationlist.AttachmentPendingFile
import io.github.vinceglb.filekit.PlatformFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class ViewOnceToolChipTest {

    private fun image() = AttachmentPendingFile.FileImage(id = Uuid.random(), file = PlatformFile("/tmp/i.png"))

    private fun toolset(current: AttachmentPendingFile?, count: Int) =
        editorToolsetFor(
            current,
            canCrop = true,
            canDraw = true,
            canSave = true,
            canSetViewOnce = true,
            attachmentCount = count,
        )

    @Test
    fun chipIsShownForASingleImageAndTogglesOnClick() = runComposeUiTest {
        var clicks = 0
        setContent {
            MaterialTheme {
                ViewOnceToolChip(toolset(image(), 1), selected = false, onClick = { clicks++ })
            }
        }
        onNodeWithTag(VIEW_ONCE_CHIP_TAG).assertIsDisplayed().performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun chipIsHiddenForTwoAttachmentsAStickerAndADocument() = runComposeUiTest {
        var current by mutableStateOf(toolset(image(), 1))
        setContent {
            MaterialTheme { ViewOnceToolChip(current, selected = false, onClick = {}) }
        }
        assertEquals(1, onAllNodesWithTag(VIEW_ONCE_CHIP_TAG).fetchSemanticsNodes().size)

        val sticker = image().copy(forceSticker = true)
        val document = AttachmentPendingFile.File(id = Uuid.random(), file = PlatformFile("/tmp/f.pdf"))
        for (hidden in listOf(toolset(image(), 2), toolset(sticker, 1), toolset(document, 1))) {
            current = hidden
            waitForIdle()
            assertEquals(0, onAllNodesWithTag(VIEW_ONCE_CHIP_TAG).fetchSemanticsNodes().size)
        }
    }
}
