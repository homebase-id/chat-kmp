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
import com.russhwolf.settings.Settings
import id.homebase.core.settings.UserPreferences
import kotlinx.collections.immutable.persistentListOf
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Minimal in-memory Settings for tests — avoids pulling in the
// multiplatform-settings-test artifact just to get MapSettings.
// ---------------------------------------------------------------------------
private class InMemorySettings : Settings {
    private val backing = mutableMapOf<String, Any>()
    override val keys: Set<String> get() = backing.keys.toSet()
    override val size: Int get() = backing.size
    override fun clear() = backing.clear()
    override fun remove(key: String) { backing.remove(key) }
    override fun hasKey(key: String): Boolean = backing.containsKey(key)
    override fun putInt(key: String, value: Int) { backing[key] = value }
    override fun getInt(key: String, defaultValue: Int): Int = (backing[key] as? Int) ?: defaultValue
    override fun getIntOrNull(key: String): Int? = backing[key] as? Int
    override fun putLong(key: String, value: Long) { backing[key] = value }
    override fun getLong(key: String, defaultValue: Long): Long = (backing[key] as? Long) ?: defaultValue
    override fun getLongOrNull(key: String): Long? = backing[key] as? Long
    override fun putString(key: String, value: String) { backing[key] = value }
    override fun getString(key: String, defaultValue: String): String = (backing[key] as? String) ?: defaultValue
    override fun getStringOrNull(key: String): String? = backing[key] as? String
    override fun putFloat(key: String, value: Float) { backing[key] = value }
    override fun getFloat(key: String, defaultValue: Float): Float = (backing[key] as? Float) ?: defaultValue
    override fun getFloatOrNull(key: String): Float? = backing[key] as? Float
    override fun putDouble(key: String, value: Double) { backing[key] = value }
    override fun getDouble(key: String, defaultValue: Double): Double = (backing[key] as? Double) ?: defaultValue
    override fun getDoubleOrNull(key: String): Double? = backing[key] as? Double
    override fun putBoolean(key: String, value: Boolean) { backing[key] = value }
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean = (backing[key] as? Boolean) ?: defaultValue
    override fun getBooleanOrNull(key: String): Boolean? = backing[key] as? Boolean
}

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
