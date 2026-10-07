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
    fun tappingTheRowEmitsViewOnlyToggledTrueThenFalse() = runComposeUiTest {
        var checked by mutableStateOf(false)
        val actions = mutableListOf<WebDropUiAction>()
        setContent {
            MaterialTheme {
                WebDropViewOnlyRow(
                    checked = checked,
                    onCheckedChange = {
                        actions += WebDropUiAction.ViewOnlyToggled(it)
                        checked = it
                    },
                )
            }
        }

        onNodeWithText("View only").performClick()
        waitForIdle()
        assertEquals(listOf<WebDropUiAction>(WebDropUiAction.ViewOnlyToggled(true)), actions)

        onNodeWithText("View only").performClick()
        waitForIdle()
        assertEquals(
            listOf<WebDropUiAction>(WebDropUiAction.ViewOnlyToggled(true), WebDropUiAction.ViewOnlyToggled(false)),
            actions,
        )
    }
}
