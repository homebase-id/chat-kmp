package id.homebase.chat.widget

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import id.homebase.core.ui.theme.HomebaseTheme
import kotlin.test.Test

/**
 * The fullscreen attachment editor's caption field must hide its rich-text
 * formatting toolbar on mobile (mirroring the chat composer, which gates the same
 * toolbar behind isDesktopOrWeb()). Driven by the [showFormattingToolbar] flag so
 * both branches are testable without a device.
 */
@OptIn(ExperimentalTestApi::class)
class AttachmentToolbarVisibilityTest {

    @Test
    fun toolbarHiddenWhenFlagFalse() = runComposeUiTest {
        setContent {
            HomebaseTheme {
                MessageTextFieldForAttachment(
                    state = rememberRichTextState(),
                    onSmileyClick = {},
                    onSendMessage = {},
                    showFormattingToolbar = false,
                )
            }
        }
        waitForIdle()
        onNodeWithTag("attachment_formatting_toolbar").assertDoesNotExist()
    }

    @Test
    fun toolbarShownWhenFlagTrue() = runComposeUiTest {
        setContent {
            HomebaseTheme {
                MessageTextFieldForAttachment(
                    state = rememberRichTextState(),
                    onSmileyClick = {},
                    onSendMessage = {},
                    showFormattingToolbar = true,
                )
            }
        }
        waitForIdle()
        onNodeWithTag("attachment_formatting_toolbar").assertExists()
    }
}
