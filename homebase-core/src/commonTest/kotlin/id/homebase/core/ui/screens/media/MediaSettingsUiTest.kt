package id.homebase.core.ui.screens.media

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runComposeUiTest
import id.homebase.core.test.setTestLocale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MediaSettingsUiTest {

    @Test
    fun theCameraPageHoldsOnlyTheCamera() = runComposeUiTest {
        setTestLocale("en-US")
        setContent { MaterialTheme { MediaSettingsUi(uiState = MediaSettingsUiState(), onAction = {}, onBackClick = {}) } }
        onNodeWithText("Camera").assertExists()
        onNodeWithTag("mirrorFrontCameraToggle").assertExists()
        onNodeWithTag("autoSaveToggle").assertDoesNotExist()
        onNodeWithText("Standard").assertDoesNotExist()
    }

    @Test
    fun turningMirrorFrontCameraOffEmitsTheAction() = runComposeUiTest {
        setTestLocale("en-US")
        var enabled: Boolean? = null
        setContent {
            MaterialTheme {
                MediaSettingsUi(
                    uiState = MediaSettingsUiState(mirrorFrontCamera = true),
                    onAction = { action ->
                        if (action is MediaSettingsUiAction.SetMirrorFrontCamera) {
                            enabled = action.enabled
                        }
                    },
                    onBackClick = {},
                )
            }
        }
        onNodeWithTag("mirrorFrontCameraToggle").performScrollTo().performClick()
        assertEquals(false, enabled)
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
