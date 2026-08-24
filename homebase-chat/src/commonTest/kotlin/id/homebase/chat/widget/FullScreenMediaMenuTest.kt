package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class FullScreenMediaMenuTest {

    @Test
    fun rendersNoItemsWhenNoHandlersGiven() = runComposeUiTest {
        setContent {
            MaterialTheme {
                FullScreenMediaMenu(showMenu = true, dismissMenu = {})
            }
        }
        waitForIdle()
        assertEquals(0, onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun rendersSaveAndDeleteWhenHandlersGiven() = runComposeUiTest {
        setContent {
            MaterialTheme {
                FullScreenMediaMenu(
                    showMenu = true,
                    dismissMenu = {},
                    onSave = {},
                    onDelete = {},
                )
            }
        }
        waitForIdle()
        assertEquals(2, onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
    }
}
