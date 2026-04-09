package id.homebase.core.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ReactionMenuTest {

    @Test
    fun showsDefaultReactions_whenNoUserReactions() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ReactionMenu(
                    onSelect = {},
                    onShowAllEmojis = {},
                )
            }
        }
        onNodeWithText("\u2764\uFE0F").assertExists()  // ❤️
        onNodeWithText("\uD83D\uDC4D").assertExists()  // 👍
        onNodeWithText("\uD83D\uDC4E").assertExists()  // 👎
        onNodeWithText("\uD83D\uDE02").assertExists()  // 😂
    }

    @Test
    fun selectCallbackFires() = runComposeUiTest {
        var selected = ""
        setContent {
            MaterialTheme {
                ReactionMenu(
                    onSelect = { selected = it },
                    onShowAllEmojis = {},
                )
            }
        }
        onNodeWithText("\uD83D\uDC4D").performClick()  // 👍
        assertEquals("\uD83D\uDC4D", selected)
    }

    @Test
    fun showsMoreButton() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ReactionMenu(
                    onSelect = {},
                    onShowAllEmojis = {},
                )
            }
        }
        onNodeWithTag("emoji_options_button").assertExists()
    }

    @Test
    fun moreButtonFiresShowAllEmojis() = runComposeUiTest {
        var showAll = false
        setContent {
            MaterialTheme {
                ReactionMenu(
                    onSelect = {},
                    onShowAllEmojis = { showAll = true },
                )
            }
        }
        onNodeWithTag("emoji_options_button").performClick()
        assertTrue(showAll)
    }

    @Test
    fun userReactionsReplaceDefaults() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ReactionMenu(
                    userDefaultReactions = persistentListOf("\uD83C\uDF89", "\uD83D\uDD25"),  // 🎉, 🔥
                    onSelect = {},
                    onShowAllEmojis = {},
                )
            }
        }
        onNodeWithText("\uD83C\uDF89").assertExists()  // 🎉
        onNodeWithText("\uD83D\uDD25").assertExists()  // 🔥
        // Default reactions should fill remaining slots
        onNodeWithText("\u2764\uFE0F").assertExists()  // ❤️
        onNodeWithText("\uD83D\uDC4D").assertExists()  // 👍
    }
}
