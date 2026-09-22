package id.homebase.chat.conversationlist

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.mohamedrejeb.richeditor.model.RichTextState
import id.homebase.chat.archivedconversations.ArchivedConversationsUiState
import id.homebase.core.ui.theme.HomebaseTheme
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Two-pane layouts lift the read-only media viewers out of the detail pane so they own the whole
 * window. Rendered on the JVM host renderer at a width that resolves to the expanded layout.
 */
@OptIn(ExperimentalTestApi::class)
class HoistedMediaViewerLayerTest {

    @Composable
    private fun Subject(messages: MessageListUiState) {
        HomebaseTheme {
            ConversationListUi(
                snackbarHostState = SnackbarHostState(),
                uiState = ConversationListUiState(),
                messagesUiState = messages,
                archivedConversationsUiState = ArchivedConversationsUiState(),
                conversationSearchTextFieldState = TextFieldState(),
                messagesSearchTextState = TextFieldState(),
                messageInputTextFieldState = RichTextState(),
                onUiAction = {},
                onNavigateToSettingsScreen = {},
            )
        }
    }

    @Test
    fun `viewer chrome is drawn over the list pane`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            val title = "quarterly-report.pdf"
            setContent {
                Subject(
                    MessageListUiState(
                        fullScreenOverlay = FullScreenOverlay.PdfViewerData(
                            messageId = Uuid.random(),
                            fileId = Uuid.random(),
                            payloadKey = "chat_web0",
                            title = title,
                            userDate = Instant.fromEpochMilliseconds(1_700_000_000_000),
                        )
                    )
                )
            }
            waitForIdle()
            // The list pane is 360.dp wide, so anything the detail pane draws starts to the right
            // of it — a title this far left can only come from a viewer above the whole scaffold.
            val left = onNodeWithText(title).getUnclippedBoundsInRoot().left
            assertTrue(left < 300.dp, "viewer title at $left is still inside the detail pane")
        }

    @Test
    fun `closed viewer layer does not swallow input`() =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            setContent { Subject(MessageListUiState()) }
            waitForIdle()
            onNodeWithText("Search").performClick()
            waitForIdle()
            onNodeWithText("Search").assertIsFocused()
        }
}
