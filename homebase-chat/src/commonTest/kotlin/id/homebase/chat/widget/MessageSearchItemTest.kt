package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.time.Instant

@OptIn(ExperimentalTestApi::class)
class MessageSearchItemTest {

    @Test
    fun rendersSimpleMarkdownMessage() = runComposeUiTest {
        setContent {
            MaterialTheme {
                MessageSearchItem(
                    memberName = "Alice",
                    message = "Hello **world**",
                    contactOdinId = null,
                    timestamp = Instant.fromEpochMilliseconds(0),
                    onClick = {},
                    onContactClick = {},
                )
            }
        }
        onNodeWithText("Alice").assertExists()
    }

    // Regression: the search-results row used to call richeditor setMarkdown()
    // directly, which threw StringIndexOutOfBoundsException inside that parser
    // for certain inputs (see RichTextRenderingTest for the exact input). The
    // row now strips to plain text via the shared CommonMark AST walk
    // (markdownToPlainPreview) before rendering, so the crash class is gone.
    // This composes the widget with that same known-problematic input and
    // asserts composition does not throw.
    @Test
    fun rendersWithoutCrashing_forParserBreakingMarkdown() = runComposeUiTest {
        val problematic = "gg\n\n  gg"
        setContent {
            MaterialTheme {
                MessageSearchItem(
                    memberName = "Bob",
                    message = problematic,
                    contactOdinId = null,
                    timestamp = Instant.fromEpochMilliseconds(0),
                    onClick = {},
                    onContactClick = {},
                )
            }
        }
        onNodeWithText("Bob").assertExists()
    }
}
