package id.homebase.chat.calling.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import id.homebase.api.common.OdinId
import id.homebase.chat.calling.CallLogDirection
import id.homebase.chat.calling.CallOutcome
import id.homebase.chat.services.calling.history.CallHistoryEntry
import id.homebase.core.ui.theme.HomebaseTheme
import kotlinx.datetime.LocalDate
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class CallsScreenScreenshotTest {
    private val outDir = File("build/calls-shots").apply { mkdirs() }

    private fun entry(
        at: String,
        direction: CallLogDirection,
        outcome: CallOutcome,
        video: Boolean = false,
        seconds: Long = 0,
        missed: Boolean = false,
    ) = CallHistoryEntry(
        messageId = Uuid.random(), fileId = Uuid.random(), conversationId = Uuid.random(),
        peer = OdinId("samwise.gamgee.demo.rocks"), direction = direction, outcome = outcome, missed = missed,
        video = video, durationSeconds = seconds, at = Instant.parse(at),
    )

    private fun row(name: String, entry: CallHistoryEntry, canCall: Boolean = true) =
        CallRowUi(entry, name, name.take(1), avatarModel = null, canCall = canCall)

    private val mixed = CallsUiState(
        sections = listOf(
            CallSectionUi(
                CallDay.Today,
                listOf(
                    row("Samwise Gamgee", entry("2026-10-13T09:41:00Z", CallLogDirection.Incoming, CallOutcome.NoAnswer, missed = true)),
                    row("Frodo Baggins", entry("2026-10-13T08:05:00Z", CallLogDirection.Outgoing, CallOutcome.Completed, video = true, seconds = 183)),
                ),
            ),
            CallSectionUi(
                CallDay.Yesterday,
                listOf(row("Meriadoc Brandybuck", entry("2026-10-12T19:20:00Z", CallLogDirection.Incoming, CallOutcome.Completed, seconds = 47), canCall = false)),
            ),
            CallSectionUi(
                CallDay.On(LocalDate(2026, 10, 3)),
                listOf(row("Peregrin Took", entry("2026-10-03T12:00:00Z", CallLogDirection.Outgoing, CallOutcome.Declined, video = true))),
            ),
        ),
        isLoading = false,
    )

    private val avatar: @androidx.compose.runtime.Composable (CallRowUi) -> Unit = { r ->
        Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text(r.avatarInitials, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.show(
        state: CallsUiState,
        dark: Boolean = false,
        rtl: Boolean = false,
        actions: MutableList<CallsAction> = mutableListOf(),
        nav: MutableList<Boolean> = mutableListOf(),
    ) = setContent {
        HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                CallsContent(state, onAction = { actions += it }, showInNav = true, onShowInNavChange = { nav += it }, avatar = avatar)
            }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.save(name: String) {
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        val colors = HashSet<Int>()
        for (x in 0 until image.width step 7) for (y in 0 until image.height step 7) colors += image.getRGB(x, y)
        assertTrue(colors.size > 8, "$name rendered blank")
        ImageIO.write(image, "png", File(outDir, "$name.png"))
    }

    @Test
    fun phoneListShowsDaysRowsKindsAndDurations() = runDesktopComposeUiTest(411, 900) {
        show(mixed)
        onNodeWithText("Today").assertExists()
        onNodeWithText("Yesterday").assertExists()
        onNodeWithText("Samwise Gamgee").assertExists()
        onNodeWithText("Missed call · Voice call").assertExists()
        onNodeWithText("Outgoing call · Video call · 3 min 3 s").assertExists()
        onNodeWithText("Call declined · Video call").assertExists()
        save("phone-light")
    }

    @Test
    fun emptyStateExplainsAndDarkThemeRenders() {
        runDesktopComposeUiTest(411, 900) {
            show(CallsUiState(isLoading = false, isEmpty = true))
            onNodeWithText("No calls yet").assertExists()
            save("empty-light")
        }
        runDesktopComposeUiTest(411, 900) {
            show(mixed, dark = true)
            save("phone-dark")
        }
    }

    @Test
    fun rtlMirrorsTheRows() = runDesktopComposeUiTest(411, 900) {
        show(mixed, rtl = true)
        onNodeWithText("Frodo Baggins").assertExists()
        save("phone-rtl")
    }

    @Test
    fun longPressOpensMenuAndDeleteAsksForConfirmation() = runDesktopComposeUiTest(411, 900) {
        val actions = mutableListOf<CallsAction>()
        show(mixed, actions = actions)
        onNodeWithText("Frodo Baggins").performTouchInput { longClick() }
        onNodeWithText("Open chat").assertExists()
        save("menu-open")
        onNodeWithText("Delete from history").performClick()
        onNodeWithText("Delete this call from your history?", substring = true).assertExists()
        assertTrue(actions.isEmpty())
        onNodeWithText("Delete").performClick()
        assertEquals(1, actions.size)
        assertTrue(actions.single() is CallsAction.Delete)
    }

    @Test
    fun callButtonMatchesKindAndIsDisabledWhenNotCallable() = runDesktopComposeUiTest(411, 900) {
        val actions = mutableListOf<CallsAction>()
        show(mixed, actions = actions)
        val video = onAllNodes(hasContentDescription("Video call")).fetchSemanticsNodes()
        assertEquals(2, video.size)
        onAllNodes(hasContentDescription("Voice call"))[0].performClick()
        assertTrue(actions.single() is CallsAction.CallBack)
        onAllNodes(hasContentDescription("Voice call"))[1].assertIsNotEnabled()
    }
}
