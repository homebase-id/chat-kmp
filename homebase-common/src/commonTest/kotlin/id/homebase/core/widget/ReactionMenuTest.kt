package id.homebase.core.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
    fun dedupesVS16Variants_noDoubleThumbs() = runComposeUiTest {
        // userDefault has the bare-codepoint form; baseDefaults has the VS-16 form.
        // Both render as the same thumbs-up but compare unequal as Strings.
        setContent {
            MaterialTheme {
                ReactionMenu(
                    userDefaultReactions = persistentListOf("👍"),  // bare 👍
                    onSelect = {},
                    onShowAllEmojis = {},
                )
            }
        }
        // Only one rendition of either form should appear.
        val bare = onAllNodesWithText("👍").fetchSemanticsNodes().size
        val vs16 = onAllNodesWithText("👍️").fetchSemanticsNodes().size
        assertEquals(1, bare + vs16, "Expected exactly one thumbs-up in the popup")
    }

    @Test
    fun ownReactionEmojiIsHighlighted() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ReactionMenu(
                    ownReactions = persistentListOf("👍"),  // 👍
                    onSelect = {},
                    onShowAllEmojis = {},
                )
            }
        }
        onAllNodesWithTag("reaction_chip_own").assertCountEquals(1)
    }

    @Test
    fun ownReactionMatchesAcrossVS16Variants() = runComposeUiTest {
        // ownReactions has the VS-16 form; popup row has the bare form (or vice versa).
        // The chip should still highlight.
        setContent {
            MaterialTheme {
                ReactionMenu(
                    ownReactions = persistentListOf("👍️"),  // 👍 + VS-16
                    onSelect = {},
                    onShowAllEmojis = {},
                )
            }
        }
        onAllNodesWithTag("reaction_chip_own").assertCountEquals(1)
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
