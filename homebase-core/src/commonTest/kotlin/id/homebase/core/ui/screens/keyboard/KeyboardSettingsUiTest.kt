package id.homebase.core.ui.screens.keyboard

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class KeyboardSettingsUiTest {

    @Test
    fun eachToggleWritesItsOwnPreference() = runComposeUiTest {
        val actions = mutableListOf<KeyboardSettingsUiAction>()
        setContent {
            MaterialTheme {
                KeyboardSettingsUi(
                    uiState = KeyboardSettingsUiState(enterSendsMessage = false, arrowUpEditsLastMessage = true),
                    onAction = { actions += it },
                    onBackClick = {},
                )
            }
        }

        onNodeWithTag("enterSendsToggle").performClick()
        onNodeWithTag("arrowUpEditsToggle").performClick()

        assertEquals(
            listOf(
                KeyboardSettingsUiAction.SetEnterSendsMessage(true),
                KeyboardSettingsUiAction.SetArrowUpEditsLastMessage(false),
            ),
            actions,
        )
    }
}
