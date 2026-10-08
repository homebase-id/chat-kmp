package id.homebase.chat.viewonce

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
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
        descriptor: ViewOnceDescriptor? = photo,
        isGroup: Boolean = false,
        openedCount: Int = 0,
    ) = setContent {
        MaterialTheme {
            Bubble(canView, isOutgoing, state, onOpen, descriptor, isGroup, openedCount)
        }
    }

    @Composable
    private fun Bubble(
        canView: Boolean,
        isOutgoing: Boolean,
        state: ViewOnceState,
        onOpen: (() -> Unit)?,
        descriptor: ViewOnceDescriptor?,
        isGroup: Boolean = false,
        openedCount: Int = 0,
    ) {
        ViewOnceBubble(
            descriptor = descriptor,
            isOutgoing = isOutgoing,
            isGroup = isGroup,
            openedCount = openedCount,
            shape = RoundedCornerShape(12),
            containerColor = Color.LightGray,
            contentColor = Color.Black,
            modifier = Modifier,
            state = state,
            canView = canView,
            onOpen = onOpen,
            footer = { Text("10:42 AM") },
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

    @Test
    fun everyOpenedPillCarriesItsKindAndTheTimeOnBothSides() = runComposeUiTest {
        for (isOutgoing in listOf(false, true)) {
            for ((descriptor, kindWord) in listOf(
                photo to "Photo",
                ViewOnceDescriptor(ViewOnceDescriptor.KIND_VIDEO) to "Video",
                null to "Media",
            )) {
                bubble(canView = true, isOutgoing = isOutgoing, state = ViewOnceState.Opened, onOpen = null, descriptor = descriptor)
                onNodeWithText("Opened").assertExists()
                onNodeWithText(kindWord).assertExists()
                onNodeWithText("10:42 AM").assertExists()
            }
        }
    }

    @Test
    fun anOpenedPillIsNotClickableEvenWhereViewingIsAllowed() = runComposeUiTest {
        var opened = 0
        bubble(canView = true, isOutgoing = false, state = ViewOnceState.Opened, onOpen = { opened++ })

        onNodeWithText("Opened").performClick()
        waitForIdle()

        assertEquals(0, opened)
    }

    @Test
    fun aGroupSenderSeesOpenedByNFromTheFirstViewerOn() = runComposeUiTest {
        for (count in listOf(1, 2, 5)) {
            bubble(canView = true, isOutgoing = true, state = ViewOnceState.Opened, onOpen = null, isGroup = true, openedCount = count)
            onNodeWithText("Opened by $count").assertExists()
        }
    }

    @Test
    fun aOneToOneSenderSeesPlainOpenedWhateverTheCount() = runComposeUiTest {
        bubble(canView = true, isOutgoing = true, state = ViewOnceState.Opened, onOpen = null, isGroup = false, openedCount = 1)
        onNodeWithText("Opened").assertExists()
        onNodeWithText("Opened by 1").assertDoesNotExist()
    }

    @Test
    fun aGroupRecipientsOwnSpentCopyNeverReadsOpenedBy() = runComposeUiTest {
        bubble(canView = true, isOutgoing = false, state = ViewOnceState.Opened, onOpen = null, isGroup = true, openedCount = 2)
        onNodeWithText("Opened").assertExists()
        onNodeWithText("Opened by 2").assertDoesNotExist()
    }

    @Test
    fun aLongPressOnAnOpenablePillIsHandedUpSoReplyAndReactStayReachable() = runComposeUiTest {
        var opened = 0
        var longPressed = 0
        setContent {
            MaterialTheme {
                ViewOnceBubble(
                    descriptor = photo,
                    isOutgoing = false,
                    shape = RoundedCornerShape(12),
                    containerColor = Color.LightGray,
                    contentColor = Color.Black,
                    state = ViewOnceState.Unopened,
                    onOpen = { opened++ },
                    onLongClick = { longPressed++ },
                    footer = { Text("10:42 AM") },
                )
            }
        }

        onNodeWithText("Photo").performTouchInput { longClick() }
        waitForIdle()

        assertEquals(1, longPressed)
        assertEquals(0, opened, "a long press is not a view")
    }

    @Test
    fun aTapStillOpensWhenALongPressIsWired() = runComposeUiTest {
        var opened = 0
        setContent {
            MaterialTheme {
                ViewOnceBubble(
                    descriptor = photo,
                    isOutgoing = false,
                    shape = RoundedCornerShape(12),
                    containerColor = Color.LightGray,
                    contentColor = Color.Black,
                    state = ViewOnceState.Unopened,
                    onOpen = { opened++ },
                    onLongClick = {},
                    footer = { Text("10:42 AM") },
                )
            }
        }

        onNodeWithText("Photo").performClick()
        waitForIdle()

        assertEquals(1, opened)
    }
}
