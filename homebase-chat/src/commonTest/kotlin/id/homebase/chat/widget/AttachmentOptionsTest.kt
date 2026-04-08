package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AttachmentOptionsTest {

    @Test
    fun displaysGalleryAndFileOptions() = runComposeUiTest {
        setContent {
            MaterialTheme {
                AttachmentOptions(
                    onGalleryClick = {},
                    onFileClick = {},
                    onContactClick = {},
                    onLocationClick = {},
                )
            }
        }
        onNodeWithText("Gallery").assertExists()
        onNodeWithText("File").assertExists()
    }

    @Test
    fun galleryClickCallbackFires() = runComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                AttachmentOptions(
                    onGalleryClick = { clicked = true },
                    onFileClick = {},
                    onContactClick = {},
                    onLocationClick = {},
                )
            }
        }
        onNodeWithText("Gallery").performClick()
        assertTrue(clicked)
    }

    @Test
    fun fileClickCallbackFires() = runComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                AttachmentOptions(
                    onGalleryClick = {},
                    onFileClick = { clicked = true },
                    onContactClick = {},
                    onLocationClick = {},
                )
            }
        }
        onNodeWithText("File").performClick()
        assertTrue(clicked)
    }
}
