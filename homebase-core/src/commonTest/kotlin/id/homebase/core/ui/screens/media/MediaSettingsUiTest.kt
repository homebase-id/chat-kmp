package id.homebase.core.ui.screens.media

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.api.image.MediaQuality
import id.homebase.core.test.setTestLocale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MediaSettingsUiTest {

    @Test
    fun showsTitleAndBothQualities() = runComposeUiTest {
        setTestLocale("en-US")
        setContent {
            MaterialTheme {
                MediaSettingsUi(
                    uiState = MediaSettingsUiState(),
                    onAction = {},
                    onBackClick = {},
                )
            }
        }
        onNodeWithText("Media").assertExists()
        onNodeWithText("Standard").assertExists()
        onNodeWithText("High").assertExists()
    }

    @Test
    fun currentQualityRendersSelected() = runComposeUiTest {
        setTestLocale("en-US")
        setContent {
            MaterialTheme {
                MediaSettingsUi(
                    uiState = MediaSettingsUiState(mediaQuality = MediaQuality.HIGH),
                    onAction = {},
                    onBackClick = {},
                )
            }
        }
        onNodeWithTag(MediaQuality.HIGH.code).assertIsSelected()
    }

    @Test
    fun selectingHighEmitsTheAction() = runComposeUiTest {
        setTestLocale("en-US")
        var selected: MediaQuality? = null
        setContent {
            MaterialTheme {
                MediaSettingsUi(
                    uiState = MediaSettingsUiState(mediaQuality = MediaQuality.STANDARD),
                    onAction = { action ->
                        if (action is MediaSettingsUiAction.SetMediaQuality) selected = action.quality
                    },
                    onBackClick = {},
                )
            }
        }
        onNodeWithTag(MediaQuality.HIGH.code).performClick()
        assertEquals(MediaQuality.HIGH, selected)
    }

    @Test
    fun theWifiGuardRowAppearsOnlyWhileAutoSaveIsOn() = runComposeUiTest {
        setTestLocale("en-US")
        setContent {
            MaterialTheme {
                MediaSettingsUi(
                    uiState = MediaSettingsUiState(autoSaveIncomingMedia = false),
                    onAction = {},
                    onBackClick = {},
                )
            }
        }
        onNodeWithTag("autoSaveToggle").assertExists()
        onNodeWithTag("autoSaveUnmeteredToggle").assertDoesNotExist()
    }

    @Test
    fun togglingAutoSaveEmitsTheAction() = runComposeUiTest {
        setTestLocale("en-US")
        var enabled: Boolean? = null
        setContent {
            MaterialTheme {
                MediaSettingsUi(
                    uiState = MediaSettingsUiState(autoSaveIncomingMedia = false),
                    onAction = { action ->
                        if (action is MediaSettingsUiAction.SetAutoSaveIncomingMedia) {
                            enabled = action.enabled
                        }
                    },
                    onBackClick = {},
                )
            }
        }
        onNodeWithTag("autoSaveToggle").performClick()
        assertEquals(true, enabled)
    }

    @Test
    fun backClickFires() = runComposeUiTest {
        setTestLocale("en-US")
        var backClicked = false
        setContent {
            MaterialTheme {
                MediaSettingsUi(
                    uiState = MediaSettingsUiState(),
                    onAction = {},
                    onBackClick = { backClicked = true },
                )
            }
        }
        onNodeWithContentDescription("Back").performClick()
        assertTrue(backClicked)
    }
}
