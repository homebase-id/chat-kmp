package id.homebase.chat.viewonce

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ViewOnceBubblePlatformTest {

    private val photo = ViewOnceDescriptor(ViewOnceDescriptor.KIND_IMAGE)

    private fun ComposeUiTest.bubble(
        canView: Boolean,
        isOutgoing: Boolean,
        state: ViewOnceState,
        onOpen: (() -> Unit)?,
    ) = setContent {
        MaterialTheme {
            Bubble(canView, isOutgoing, state, onOpen)
        }
    }

    @Composable
    private fun Bubble(canView: Boolean, isOutgoing: Boolean, state: ViewOnceState, onOpen: (() -> Unit)?) {
        ViewOnceBubble(
            descriptor = photo,
            isOutgoing = isOutgoing,
            shape = RoundedCornerShape(12),
            containerColor = Color.LightGray,
            contentColor = Color.Black,
            modifier = Modifier,
            state = state,
            canView = canView,
            onOpen = onOpen,
        )
    }

    @Test
    fun anUnopenedIncomingCopyWhereViewingIsBlockedExplainsInsteadOfOpening() = runComposeUiTest {
        var opened = 0
        bubble(canView = false, isOutgoing = false, state = ViewOnceState.Unopened, onOpen = { opened++ })

        onNodeWithText("Open on your phone").assertExists()
        onNodeWithText("Open on your phone").performClick()
        waitForIdle()

        onNodeWithText("Open this on your phone").assertExists()
        onNodeWithText("Ok").performClick()
        waitForIdle()
        onNodeWithText("Open this on your phone").assertDoesNotExist()
        assertEquals(0, opened)
    }

    @Test
    fun whereViewingIsAllowedTheChipOpensAndNoDialogShows() = runComposeUiTest {
        var opened = 0
        bubble(canView = true, isOutgoing = false, state = ViewOnceState.Unopened, onOpen = { opened++ })

        onNodeWithText("Photo").performClick()
        waitForIdle()

        assertEquals(1, opened)
        onNodeWithText("Open this on your phone").assertDoesNotExist()
    }

    @Test
    fun theSenderSideReadsTheSameWhetherOrNotThePlatformCanView() = runComposeUiTest {
        for (state in listOf(ViewOnceState.Sent, ViewOnceState.Opened)) {
            for (canView in listOf(true, false)) {
                bubble(canView = canView, isOutgoing = true, state = state, onOpen = null)
                if (state == ViewOnceState.Sent) {
                    // The ticks say "sent" on screen; the chip says it to a screen reader.
                    onNode(hasText("Photo") and hasStateDescription("Sent")).assertExists()
                } else {
                    onNodeWithText("Opened").assertExists()
                }
                onNodeWithText("Open on your phone").assertDoesNotExist()
            }
        }
    }
}
