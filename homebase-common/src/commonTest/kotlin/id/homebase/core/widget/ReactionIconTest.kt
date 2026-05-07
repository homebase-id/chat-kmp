package id.homebase.core.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReactionIconTest {

    @Test
    fun displaysEmoji() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ReactionIcon(
                    emoji = "\uD83D\uDC4D",
                    count = 1,
                    onClick = {},
                )
            }
        }
        onNodeWithText("\uD83D\uDC4D").assertExists()
    }

    @Test
    fun hidesCount_whenOne() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ReactionIcon(
                    emoji = "\uD83D\uDC4D",
                    count = 1,
                    onClick = {},
                )
            }
        }
        // count "1" should not be displayed when count == 1
        onNodeWithText("1").assertDoesNotExist()
    }

    @Test
    fun showsCount_whenGreaterThanOne() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ReactionIcon(
                    emoji = "\uD83D\uDC4D",
                    count = 5,
                    onClick = {},
                )
            }
        }
        onNodeWithText("5").assertExists()
    }

    @Test
    fun clickCallbackFires() = runComposeUiTest {
        var clicked = false
        setContent {
            MaterialTheme {
                ReactionIcon(
                    emoji = "\uD83D\uDC4D",
                    count = 1,
                    onClick = { clicked = true },
                )
            }
        }
        onNodeWithText("\uD83D\uDC4D").performClick()
        assertTrue(clicked)
    }
}
