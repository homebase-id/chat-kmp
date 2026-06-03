package id.homebase.core.ui.screens.appearance

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.core.settings.Language
import id.homebase.core.settings.ThemeState
import id.homebase.core.test.setTestLocale
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class AppearanceSettingsUiTest {

    @Test
    fun showsTitle() = runComposeUiTest {
        setTestLocale("en-US")
        setContent {
            MaterialTheme {
                AppearanceSettingsUi(
                    uiState = AppearanceSettingsUiState(),
                    onAction = {},
                    onBackClick = {},
                )
            }
        }
        onNodeWithText("Appearance").assertExists()
    }

    @Test
    fun showsLanguageAndThemeRows() = runComposeUiTest {
        setTestLocale("en-US")
        setContent {
            MaterialTheme {
                AppearanceSettingsUi(
                    uiState = AppearanceSettingsUiState(),
                    onAction = {},
                    onBackClick = {},
                )
            }
        }
        onNodeWithText("Language").assertExists()
        onNodeWithText("Theme").assertExists()
    }

    @Test
    fun showsSelectedLanguage() = runComposeUiTest {
        setTestLocale("en-US")
        setContent {
            MaterialTheme {
                AppearanceSettingsUi(
                    uiState = AppearanceSettingsUiState(
                        selectedLanguage = Language.ENGLISH_US,
                    ),
                    onAction = {},
                    onBackClick = {},
                )
            }
        }
        onNodeWithText("English (USA)").assertExists()
    }

    @Test
    fun showsSelectedTheme() = runComposeUiTest {
        setTestLocale("en-US")
        setContent {
            MaterialTheme {
                AppearanceSettingsUi(
                    uiState = AppearanceSettingsUiState(
                        selectedTheme = ThemeState.Dark,
                    ),
                    onAction = {},
                    onBackClick = {},
                )
            }
        }
        onNodeWithText("Dark").assertExists()
    }

    @Test
    fun languageSelectionFiresAction() = runComposeUiTest {
        setTestLocale("en-US")
        var selectedLanguage: Language? = null
        setContent {
            MaterialTheme {
                AppearanceSettingsUi(
                    uiState = AppearanceSettingsUiState(
                        selectedLanguage = Language.SYSTEM,
                    ),
                    onAction = { action ->
                        if (action is AppearanceSettingsUiAction.LanguageSelected) {
                            selectedLanguage = action.language
                        }
                    },
                    onBackClick = {},
                )
            }
        }
        // Expand language options
        onNodeWithText("Language").performClick()
        // Select English (USA)
        onNodeWithText("English (USA)").performClick()
        assertTrue(selectedLanguage == Language.ENGLISH_US)
    }

    @Test
    fun showsHapticFeedbackRow() = runComposeUiTest {
        setTestLocale("en-US")
        setContent {
            MaterialTheme {
                AppearanceSettingsUi(
                    uiState = AppearanceSettingsUiState(),
                    onAction = {},
                    onBackClick = {},
                )
            }
        }
        onNodeWithText("Haptic Feedback").assertExists()
    }

    @Test
    fun backClickFires() = runComposeUiTest {
        setTestLocale("en-US")
        var backClicked = false
        setContent {
            MaterialTheme {
                AppearanceSettingsUi(
                    uiState = AppearanceSettingsUiState(),
                    onAction = {},
                    onBackClick = { backClicked = true },
                )
            }
        }
        onNodeWithContentDescription("Back").performClick()
        assertTrue(backClicked)
    }
}
