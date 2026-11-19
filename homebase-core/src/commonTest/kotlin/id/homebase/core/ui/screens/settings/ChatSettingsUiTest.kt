package id.homebase.core.ui.screens.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.image.MediaQuality
import id.homebase.api.image.SendQuality
import id.homebase.core.test.setTestLocale
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ChatSettingsUiTest {
    private val seen = mutableListOf<ChatSettingsUiAction>()

    private fun ComposeUiTest.chat(state: ChatSettingsUiState = ChatSettingsUiState()) {
        setTestLocale("en-US")
        setContent { MaterialTheme { ChatSettingsUi(state, { seen += it }, onBack = {}, onOpenChat = {}) } }
    }

    private fun ComposeUiTest.tap(tag: String) {
        onNodeWithTag(tag).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
        waitForIdle()
    }

    @Test
    fun photoAndVideoAreChosenApart() = runComposeUiTest {
        chat(ChatSettingsUiState(quality = SendQuality(MediaQuality.HD, MediaQuality.STANDARD)))
        tap("photo-row")
        onNodeWithTag("photo-" + MediaQuality.HD.code).assertIsSelected()
        tap("photo-" + MediaQuality.ORIGINAL.code)
        tap("video-row")
        onNodeWithTag("video-" + MediaQuality.STANDARD.code).assertIsSelected()
        // Outside Photos an Original video is HD's, so it isn't offered.
        onNodeWithTag("video-" + MediaQuality.ORIGINAL.code).assertDoesNotExist()
        tap("video-" + MediaQuality.HD.code)
        assertEquals(
            listOf<ChatSettingsUiAction>(
                ChatSettingsUiAction.SetQuality(SendQuality(MediaQuality.ORIGINAL, MediaQuality.STANDARD)),
                ChatSettingsUiAction.SetQuality(SendQuality(MediaQuality.HD, MediaQuality.HD)),
            ),
            seen,
        )
    }

    @Test
    fun theWifiGuardRowAppearsOnlyWhileAutoSaveIsOn() = runComposeUiTest {
        chat(ChatSettingsUiState(autoSaveIncomingMedia = false))
        onNodeWithTag("autoSaveToggle").assertExists()
        onNodeWithTag("autoSaveUnmeteredToggle").assertDoesNotExist()
    }

    @Test
    fun togglingAutoSaveEmitsTheAction() = runComposeUiTest {
        chat(ChatSettingsUiState(autoSaveIncomingMedia = false))
        tap("autoSaveToggle")
        assertEquals(listOf<ChatSettingsUiAction>(ChatSettingsUiAction.SetAutoSaveIncomingMedia(true)), seen)
    }
}
