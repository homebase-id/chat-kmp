package id.homebase.chat.widget

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import id.homebase.core.ui.theme.HomebaseTheme
import kotlin.test.Test
import kotlin.test.assertEquals

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
                    onSendMessage = {},
                    showFormattingToolbar = true,
                )
            }
        }
        waitForIdle()
        onNodeWithTag("attachment_formatting_toolbar").assertExists()
    }

    @Test
    fun emojiButtonTogglesPicker() = runComposeUiTest {
        setContent {
            HomebaseTheme {
                MessageTextFieldForAttachment(
                    state = rememberRichTextState(),
                    onSendMessage = {},
                    showFormattingToolbar = false,
                )
            }
        }
        waitForIdle()
        onNodeWithTag(ATTACHMENT_EMOJI_PICKER_TAG).assertDoesNotExist()

        onNodeWithTag(ATTACHMENT_EMOJI_BUTTON_TAG).performClick()
        waitForIdle()
        onNodeWithTag(ATTACHMENT_EMOJI_PICKER_TAG).assertExists()

        onNodeWithTag(ATTACHMENT_EMOJI_BUTTON_TAG).performClick()
        waitForIdle()
        onNodeWithTag(ATTACHMENT_EMOJI_PICKER_TAG).assertDoesNotExist()
    }

    @Test
    fun emojiPickerSurvivesUnfocusedCaptionAndClosesWhenItRegainsFocus() = runComposeUiTest {
        var reportedVisible: Boolean? = null
        setContent {
            HomebaseTheme {
                MessageTextFieldForAttachment(
                    state = rememberRichTextState(),
                    onSendMessage = {},
                    showFormattingToolbar = false,
                    onEmojiPickerVisibilityChanged = { reportedVisible = it },
                )
            }
        }
        waitForIdle()

        onNodeWithTag(ATTACHMENT_EMOJI_BUTTON_TAG).performClick()
        waitForIdle()
        onNodeWithTag(ATTACHMENT_EMOJI_PICKER_TAG).assertExists()
        assertEquals(true, reportedVisible)

        // The panel owns a search field of its own, so it has to stay up while the
        // caption field is unfocused -- closing it on keyboard visibility instead
        // tore it out the moment the user tapped the panel's own magnifier.
        onNodeWithTag(ATTACHMENT_CAPTION_FIELD_TAG).assertIsNotFocused()
        onNodeWithTag(ATTACHMENT_EMOJI_PICKER_TAG).assertExists()

        onNodeWithTag(ATTACHMENT_CAPTION_FIELD_TAG).requestFocus()
        waitForIdle()
        onNodeWithTag(ATTACHMENT_EMOJI_PICKER_TAG).assertDoesNotExist()
        assertEquals(false, reportedVisible)
    }
}
