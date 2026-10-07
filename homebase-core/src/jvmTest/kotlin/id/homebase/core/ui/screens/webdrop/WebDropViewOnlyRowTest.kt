package id.homebase.core.ui.screens.webdrop

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.core.ui.screens.webdrop.components.WebDropViewOnlyRow
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class WebDropViewOnlyRowTest {

    @Test
    fun tappingTheRowReportsTheFlippedValueAndTheStateReducerStoresIt() = runComposeUiTest {
        var state by mutableStateOf(WebDropUiState())
        val actions = mutableListOf<WebDropUiAction>()
        setContent {
            MaterialTheme {
                WebDropViewOnlyRow(
                    checked = state.viewOnly,
                    onCheckedChange = {
                        val action = WebDropUiAction.ViewOnlyToggled(it)
                        actions += action
                        state = state.copy(viewOnly = action.enabled)
                    },
                )
            }
        }

        onNodeWithText("View only").performClick()
        waitForIdle()
        assertEquals(listOf<WebDropUiAction>(WebDropUiAction.ViewOnlyToggled(true)), actions)
        assertEquals(true, state.viewOnly)

        onNodeWithText("View only").performClick()
        waitForIdle()
        assertEquals(false, state.viewOnly)
    }
}
