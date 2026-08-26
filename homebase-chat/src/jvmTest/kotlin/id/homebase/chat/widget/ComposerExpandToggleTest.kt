package id.homebase.chat.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.mohamedrejeb.richeditor.model.rememberRichTextState
import com.russhwolf.settings.PreferencesSettings
import id.homebase.api.client.auth.CredentialsManager
import id.homebase.api.client.link.LinkPreviewProvider
import id.homebase.core.settings.UserPreferences
import id.homebase.core.ui.theme.HomebaseTheme
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import org.koin.compose.KoinIsolatedContext
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.util.prefs.Preferences
import kotlin.test.Test

/**
 * The expand affordance has to survive its own press: the compact and the expanded composer are
 * two different composables, so a toggle wired into only one of them strands the user in the
 * expanded editor. Desktop-shaped window because the toolbar that hosts it is desktop/web-only.
 */
@OptIn(ExperimentalTestApi::class)
class ComposerExpandToggleTest {

    private val expandLabel = "Expand"
    private val collapseLabel = "Collapse"

    private val koin = koinApplication {
        modules(
            module {
                single {
                    UserPreferences(
                        PreferencesSettings(Preferences.userRoot().node("/id/homebase/test"))
                    )
                }
                single { LinkPreviewProvider(HttpClient(MockEngine { respond("") }), CredentialsManager()) }
            }
        )
    }

    @Composable
    private fun Composer() {
        KoinIsolatedContext(koin) {
            HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    MessageInputBar(
                        textFieldState = rememberRichTextState(),
                        recordingData = null,
                        focusRequester = FocusRequester(),
                        editExistingMode = false,
                        showingEmojiSheet = false,
                        onEmojiClick = {},
                        onKeyboardClick = {},
                        onFocused = {},
                        onAddAttachmentClick = {},
                        onCameraClick = {},
                        onVideoRecordClick = {},
                        onRecordingStarted = {},
                        onRecordingStopped = {},
                        onRecordingCancelled = {},
                        onRecordingHelp = {},
                        payloadRenderers = emptyList(),
                        onPayloadRenderersChange = {},
                        onSendMessage = { _, _ -> },
                        onCancelEdit = {},
                    )
                }
            }
        }
    }

    @Test
    fun `the toggle expands and collapses without hover or editor focus`() =
        runDesktopComposeUiTest(width = 1200, height = 620) {
            setContent { Composer() }
            waitForIdle()

            onNodeWithTag(COMPOSER_EXPAND_TOGGLE_TAG).assertIsEnabled()
            onNodeWithContentDescription(expandLabel).assertExists()

            onNodeWithTag(COMPOSER_EXPAND_TOGGLE_TAG).performClick()
            waitForIdle()
            onNodeWithContentDescription(collapseLabel).assertExists()

            onNodeWithTag(COMPOSER_EXPAND_TOGGLE_TAG).performClick()
            waitForIdle()
            onNodeWithContentDescription(expandLabel).assertExists()
        }
}
