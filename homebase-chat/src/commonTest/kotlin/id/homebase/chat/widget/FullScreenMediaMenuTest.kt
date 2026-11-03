package id.homebase.chat.widget

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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

    @Test
    fun rendersSaveToVaultItemWhenHandlerGiven() = runComposeUiTest {
        setContent {
            MaterialTheme {
                FullScreenMediaMenu(showMenu = true, dismissMenu = {}, onSaveToVault = {})
            }
        }
        waitForIdle()
        assertEquals(1, onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun imageOnlyActionOfferedOnlyForImagesWithHandler() {
        val handler: () -> Unit = {}
        assertNotNull(imageOnlyAction("image/jpeg", handler))
        assertNotNull(imageOnlyAction("image/webp", handler))
        assertNull(imageOnlyAction("video/mp4", handler))
        assertNull(imageOnlyAction("audio/mpeg", handler))
        assertNull(imageOnlyAction("application/pdf", handler))
        assertNull(imageOnlyAction(null, handler))
        assertNull(imageOnlyAction("image/jpeg", null))
    }
}
