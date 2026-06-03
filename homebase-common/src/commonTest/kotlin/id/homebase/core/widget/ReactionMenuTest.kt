package id.homebase.core.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.core.settings.InMemorySettings
import id.homebase.core.settings.UserPreferences
import kotlinx.collections.immutable.persistentListOf
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Koin module that satisfies the UserPreferences dependency required by
// rememberHaptics() → koinInject<UserPreferences>() inside ReactionMenu.
// ---------------------------------------------------------------------------
private val hapticsTestModule = module {
    single { UserPreferences(InMemorySettings()) }
}

/**
 * Wraps [content] inside a [KoinApplication] scoped to this composition,
 * providing [hapticsTestModule] so that [id.homebase.core.haptics.rememberHaptics]
 * can resolve [UserPreferences] without a globally-started Koin context.
 *
 * Using the composable KoinApplication (rather than startKoin/stopKoin) keeps
 * each test's Koin instance lifecycle tied to the Compose composition, avoiding
 * cross-test contamination.
 */
@Composable
private fun WithHapticsKoin(content: @Composable () -> Unit) {
    KoinApplication(configuration = koinConfiguration { modules(hapticsTestModule) }) {
        content()
    }
}

@OptIn(ExperimentalTestApi::class)
class ReactionMenuTest {

    @Test
    fun showsDefaultReactions_whenNoUserReactions() = runComposeUiTest {
        setContent {
            WithHapticsKoin {
                MaterialTheme {
                    ReactionMenu(
                        onSelect = {},
                        onShowAllEmojis = {},
                    )
                }
            }
        }
        onNodeWithText("❤️").assertExists()  // ❤️
        onNodeWithText("👍").assertExists()  // 👍
        onNodeWithText("👎").assertExists()  // 👎
        onNodeWithText("😂").assertExists()  // 😂
    }

    @Test
    fun selectCallbackFires() = runComposeUiTest {
        var selected = ""
        setContent {
            WithHapticsKoin {
                MaterialTheme {
                    ReactionMenu(
                        onSelect = { selected = it },
                        onShowAllEmojis = {},
                    )
                }
            }
        }
        onNodeWithText("👍").performClick()  // 👍
        assertEquals("👍", selected)
    }

    @Test
    fun showsMoreButton() = runComposeUiTest {
        setContent {
            WithHapticsKoin {
                MaterialTheme {
                    ReactionMenu(
                        onSelect = {},
                        onShowAllEmojis = {},
                    )
                }
            }
        }
        onNodeWithTag("emoji_options_button").assertExists()
    }

    @Test
    fun moreButtonFiresShowAllEmojis() = runComposeUiTest {
        var showAll = false
        setContent {
            WithHapticsKoin {
                MaterialTheme {
                    ReactionMenu(
                        onSelect = {},
                        onShowAllEmojis = { showAll = true },
                    )
                }
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
            WithHapticsKoin {
                MaterialTheme {
                    ReactionMenu(
                        userDefaultReactions = persistentListOf("👍"),  // bare 👍
                        onSelect = {},
                        onShowAllEmojis = {},
                    )
                }
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
            WithHapticsKoin {
                MaterialTheme {
                    ReactionMenu(
                        ownReactions = persistentListOf("👍"),  // 👍
                        onSelect = {},
                        onShowAllEmojis = {},
                    )
                }
            }
        }
        onAllNodesWithTag("reaction_chip_own").assertCountEquals(1)
    }

    @Test
    fun ownReactionMatchesAcrossVS16Variants() = runComposeUiTest {
        // ownReactions has the VS-16 form; popup row has the bare form (or vice versa).
        // The chip should still highlight.
        setContent {
            WithHapticsKoin {
                MaterialTheme {
                    ReactionMenu(
                        ownReactions = persistentListOf("👍️"),  // 👍 + VS-16
                        onSelect = {},
                        onShowAllEmojis = {},
                    )
                }
            }
        }
        onAllNodesWithTag("reaction_chip_own").assertCountEquals(1)
    }

    @Test
    fun userReactionsReplaceDefaults() = runComposeUiTest {
        setContent {
            WithHapticsKoin {
                MaterialTheme {
                    ReactionMenu(
                        userDefaultReactions = persistentListOf("🎉", "🔥"),  // 🎉, 🔥
                        onSelect = {},
                        onShowAllEmojis = {},
                    )
                }
            }
        }
        onNodeWithText("🎉").assertExists()  // 🎉
        onNodeWithText("🔥").assertExists()  // 🔥
        // Default reactions should fill remaining slots
        onNodeWithText("❤️").assertExists()  // ❤️
        onNodeWithText("👍").assertExists()  // 👍
    }
}
