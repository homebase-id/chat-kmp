package id.homebase.core.ui.screens.contactbook

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import id.homebase.api.common.OdinId
import id.homebase.chat.calling.CallLogDirection
import id.homebase.chat.calling.CallOutcome
import id.homebase.chat.calling.history.CallDay
import id.homebase.chat.calling.history.CallRowUi
import id.homebase.chat.calling.history.CallSectionUi
import id.homebase.chat.calling.history.CallsContent
import id.homebase.chat.calling.history.CallsUiState
import id.homebase.chat.services.calling.history.CallHistoryEntry
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlin.uuid.Uuid

@OptIn(ExperimentalTestApi::class)
class ContactTabRowScreenshotTest {
    private val outDir = File("../build/polish-shots").apply { mkdirs() }

    private fun entry(at: String, direction: CallLogDirection, outcome: CallOutcome, video: Boolean = false, seconds: Long = 0) =
        CallHistoryEntry(
            messageId = Uuid.random(), fileId = Uuid.random(), conversationId = Uuid.random(),
            peer = OdinId("samwise.gamgee.demo.rocks"), direction = direction, outcome = outcome,
            missed = outcome == CallOutcome.NoAnswer, video = video, durationSeconds = seconds, at = Instant.parse(at),
        )

    private val calls = CallsUiState(
        sections = listOf(
            CallSectionUi(
                CallDay.Today,
                listOf(
                    CallRowUi(entry("2026-10-13T09:41:00Z", CallLogDirection.Incoming, CallOutcome.NoAnswer), "Samwise Gamgee", "S", null, true),
                    CallRowUi(entry("2026-10-13T08:05:00Z", CallLogDirection.Outgoing, CallOutcome.Completed, true, 183), "Frodo Baggins", "F", null, true),
                ),
            ),
            CallSectionUi(
                CallDay.Yesterday,
                listOf(CallRowUi(entry("2026-10-12T19:20:00Z", CallLogDirection.Incoming, CallOutcome.Completed, seconds = 47), "Meriadoc Brandybuck", "M", null, true)),
            ),
        ),
        isLoading = false,
    )

    private fun ComposeUiTest.screen(selected: ContactTab, dark: Boolean, onNewCall: () -> Unit = {}) = setContent {
        HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
            Scaffold(floatingActionButton = { ContactBookFab(selected, onboarding = false, onAdd = {}, onNewCall = onNewCall) }) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    ContactTabRow(
                        tabs = contactTabs(callsAvailable = true),
                        selected = selected,
                        onSelect = {},
                        waiting = 3,
                        hasEnrollmentCandidates = true,
                        missedCalls = 2,
                    )
                    if (selected == ContactTab.CALLS) {
                        CallsContent(calls, onAction = {}, avatar = { r ->
                            Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                Text(r.avatarInitials, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        })
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.save(name: String) {
        ImageIO.write(onAllNodes(isRoot())[0].captureToImage().toAwtImage(), "png", File(outDir, "$name.png"))
    }

    @Test
    fun tabsKeepEveryLabelWholeAndShowTheMissedCount() {
        for (width in listOf(360, 840)) for (dark in listOf(false, true)) for (tab in listOf(ContactTab.CALLS, ContactTab.KNOWN)) {
            runDesktopComposeUiTest(width, 720) {
                screen(tab, dark)
                listOf("Contacts", "Calls", "New", "Circles").forEach { label ->
                    val bounds = onNodeWithText(label, useUnmergedTree = true).getUnclippedBoundsInRoot()
                    assertTrue(bounds.height.value < 24f, "$label wrapped to ${bounds.height}")
                }
                onNodeWithText("2", useUnmergedTree = true).assertExists()
                onNodeWithText("3", useUnmergedTree = true).assertExists()
                if (tab == ContactTab.CALLS) {
                    onNodeWithText("Beta", useUnmergedTree = true).assertExists()
                    onAllNodes(hasContentDescription("New call")).fetchSemanticsNodes().let { assertEquals(1, it.size) }
                } else {
                    onAllNodes(hasContentDescription("New call")).fetchSemanticsNodes().let { assertTrue(it.isEmpty()) }
                }
                save("${tab.name.lowercase()}-$width-${if (dark) "dark" else "light"}")
            }
        }
    }

    @Test
    fun newCallFabOpensThePicker() = runDesktopComposeUiTest(360, 720) {
        var opened = 0
        screen(ContactTab.CALLS, dark = false, onNewCall = { opened++ })
        onAllNodes(hasContentDescription("New call"))[0].performClick()
        assertEquals(1, opened)
    }
}
