package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AttachmentOptionsTest {

    private fun ComposeUiTest.setOptions(
        onGalleryClick: () -> Unit = {},
        onFileClick: () -> Unit = {},
        onContactClick: () -> Unit = {},
    ) = setContent {
        MaterialTheme {
            AttachmentOptions(
                attachmentActions(onGalleryClick, onFileClick, onContactClick, {}, {}, {}, {}, {}),
                onPicked = {},
            )
        }
    }

    @Test
    fun displaysGalleryAndFileOptions() = runComposeUiTest {
        setOptions()
        onNodeWithTag("attachment_gallery").assertExists()
        onNodeWithTag("attachment_file").assertExists()
    }

    @Test
    fun galleryClickCallbackFires() = runComposeUiTest {
        var clicked = false
        setOptions(onGalleryClick = { clicked = true })
        onNodeWithTag("attachment_gallery").performClick()
        assertTrue(clicked)
    }

    @Test
    fun fileClickCallbackFires() = runComposeUiTest {
        var clicked = false
        setOptions(onFileClick = { clicked = true })
        onNodeWithTag("attachment_file").performClick()
        assertTrue(clicked)
    }

    @Test
    fun displaysContactOption() = runComposeUiTest {
        setOptions()
        onNodeWithTag("attachment_contact").assertExists()
    }

    @Test
    fun contactClickCallbackFires() = runComposeUiTest {
        var clicked = false
        setOptions(onContactClick = { clicked = true })
        onNodeWithTag("attachment_contact").performClick()
        assertTrue(clicked)
    }
}
