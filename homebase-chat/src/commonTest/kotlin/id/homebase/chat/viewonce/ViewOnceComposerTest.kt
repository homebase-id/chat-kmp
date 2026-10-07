package id.homebase.chat.viewonce

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import id.homebase.chat.widget.ATTACHMENT_CAPTION_FIELD_TAG
import id.homebase.chat.widget.InMemorySettings
import id.homebase.chat.widget.MessageTextFieldForAttachment
import id.homebase.chat.widget.VIEW_ONCE_TOGGLE_TAG
import id.homebase.chat.widget.ViewOnceToggle
import id.homebase.chat.widget.WithComposerPreferences
import id.homebase.core.settings.UserPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ViewOnceComposerTest {

    @Test
    fun theToggleSitsInsideTheCaptionBarAndTheCaptionStaysUsable() = runComposeUiTest {
        var checked by mutableStateOf(false)
        var toggles = 0
        setContent {
            WithComposerPreferences {
                MaterialTheme {
                    MessageTextFieldForAttachment(
                        state = rememberRichTextState(),
                        onSendMessage = {},
                        showFormattingToolbar = false,
                        viewOnceToggle = ViewOnceToggle(checked) { toggles++; checked = !checked },
                    )
                }
            }
        }
        onNodeWithTag(VIEW_ONCE_TOGGLE_TAG).assertIsOff().performClick()
        waitForIdle()

        assertEquals(1, toggles)
        onNodeWithTag(VIEW_ONCE_TOGGLE_TAG).assertIsOn()
        onNodeWithTag(ATTACHMENT_CAPTION_FIELD_TAG).assertIsEnabled()
        onAllNodesWithText("View once").assertCountEquals(0)
    }

    @Test
    fun noToggleWhenTheAttachmentCannotBeViewOnce() = runComposeUiTest {
        setContent {
            WithComposerPreferences {
                MaterialTheme {
                    MessageTextFieldForAttachment(
                        state = rememberRichTextState(),
                        onSendMessage = {},
                        showFormattingToolbar = false,
                    )
                }
            }
        }
        assertEquals(0, onAllNodesWithTag(VIEW_ONCE_TOGGLE_TAG).fetchSemanticsNodes().size)
    }

    @Test
    fun theIntroShowsOnTheFirstTurnOnOnlyAndTheFlagPersists() {
        val settings = InMemorySettings()
        val state = ViewOnceComposerState(UserPreferences(settings))

        state.toggle(isVideo = false)
        assertTrue(state.requested)
        assertTrue(state.showIntro)
        assertEquals(ViewOnceToastKind.Photo, state.toast?.kind)

        state.dismissIntro()
        state.toggle(isVideo = false)
        assertFalse(state.requested)
        assertFalse(state.showIntro)
        assertEquals(ViewOnceToastKind.Off, state.toast?.kind)

        state.toggle(isVideo = true)
        assertTrue(state.requested)
        assertFalse(state.showIntro, "second turn-on in the same session")
        assertEquals(ViewOnceToastKind.Video, state.toast?.kind)

        val nextSession = ViewOnceComposerState(UserPreferences(settings))
        nextSession.toggle(isVideo = false)
        assertFalse(nextSession.showIntro, "the flag outlives the screen")
    }

    @Test
    fun everyToggleGetsItsOwnToastEvenWithTheSameKind() {
        val state = ViewOnceComposerState(UserPreferences(InMemorySettings()))
        state.toggle(isVideo = false)
        val first = assertNotNull(state.toast)
        state.toggle(isVideo = false)
        state.toggle(isVideo = false)
        assertTrue(state.toast !== first)
    }

    @Test
    fun theIntroSheetCarriesTheTwoRowsAndNothingElse() = runComposeUiTest {
        var ok = 0
        var closed = 0
        setContent { MaterialTheme { ViewOnceIntroContent(isVideo = false, onOk = { ok++ }, onClose = { closed++ }) } }

        onNodeWithText("This photo can be viewed once").assertExists()
        onNodeWithText("It disappears from the chat after it's closed").assertExists()
        onNodeWithText("It can't be shared, forwarded, copied or saved").assertExists()
        assertEquals(0, onAllNodesWithText("screenshot", substring = true, ignoreCase = true).fetchSemanticsNodes().size)
        assertEquals(0, onAllNodesWithText("Learn more", substring = true, ignoreCase = true).fetchSemanticsNodes().size)

        onNodeWithTag(VIEW_ONCE_INTRO_OK_TAG).performClick()
        onNodeWithTag(VIEW_ONCE_INTRO_CLOSE_TAG).performClick()
        assertEquals(1, ok)
        assertEquals(1, closed)
    }

    @Test
    fun theIntroNamesAVideoWhenItIsOne() = runComposeUiTest {
        setContent { MaterialTheme { ViewOnceIntroContent(isVideo = true, onOk = {}, onClose = {}) } }
        onNodeWithText("This video can be viewed once").assertExists()
    }

    @Test
    fun theToastShowsItsWordsThenGoesAway() = runComposeUiTest {
        var message by mutableStateOf<ViewOnceToastMessage?>(null)
        setContent { MaterialTheme { ViewOnceToast(message) } }
        assertEquals(0, onAllNodesWithTag(VIEW_ONCE_TOAST_TAG).fetchSemanticsNodes().size)

        message = ViewOnceToastMessage(ViewOnceToastKind.Video)
        waitForIdle()
        onNodeWithText("Video set to view once").assertExists()

        mainClock.advanceTimeBy(VIEW_ONCE_TOAST_MS + 1_000)
        waitForIdle()
        assertEquals(0, onAllNodesWithTag(VIEW_ONCE_TOAST_TAG).fetchSemanticsNodes().size)

        message = ViewOnceToastMessage(ViewOnceToastKind.Off)
        waitForIdle()
        onNodeWithText("View once off").assertExists()
    }
}
