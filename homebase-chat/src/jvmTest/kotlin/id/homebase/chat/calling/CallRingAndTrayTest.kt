package id.homebase.chat.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallPhase
import id.homebase.core.ui.theme.HomebaseTheme
import id.homebase.resources.MR
import id.homebase.resources.desktop_menu_call_hang_up
import id.homebase.resources.desktop_menu_call_mute
import id.homebase.resources.desktop_menu_call_unmute
import id.homebase.resources.desktop_tray_in_call_timed
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalTestApi::class)
class CallRingAndTrayTest {
    private val outDir = File("build/call-shots").apply { mkdirs() }

    @Test
    fun trayItemsExistOnlyWhileTheCallIsInProgress() {
        val inProgress = listOf(CallPhase.Connecting, CallPhase.Connected, CallPhase.Reconnecting)
        val notInProgress = listOf(CallPhase.Idle, CallPhase.Outgoing, CallPhase.Incoming, CallPhase.Ended(CallEndReason.Declined))
        for (phase in inProgress) {
            assertEquals(
                listOf(CallTrayItem.Status("Sam", "01:23"), CallTrayItem.Mute(false), CallTrayItem.HangUp),
                callTrayItems(CallUiState(phase = phase), "Sam", "01:23"),
                "$phase",
            )
        }
        for (phase in notInProgress) assertEquals(emptyList(), callTrayItems(CallUiState(phase = phase), "Sam"), "$phase")
    }

    @Test
    fun theMuteItemFollowsTheMutedState() {
        val items = callTrayItems(CallUiState(phase = CallPhase.Connected, muted = true), "Sam")
        assertEquals(CallTrayItem.Mute(true), items[1])
        assertEquals(CallTrayItem.Status("Sam", null), items[0])
    }

    private fun ComposeUiTest.showRing(video: Boolean, dark: Boolean, onVoice: () -> Unit = {}, onVideo: () -> Unit = {}, onDecline: () -> Unit = {}) {
        setContent {
            HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                Box(Modifier.size(RingWindowWidth, RingWindowHeight)) {
                    CallRingContent(
                        video = video,
                        name = "Samwise Gamgee",
                        avatar = { size ->
                            Box(Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                Text("SG", color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        },
                        onAcceptVoice = onVoice,
                        onAcceptVideo = onVideo,
                        onDecline = onDecline,
                    )
                }
            }
        }
    }

    private fun ComposeUiTest.shoot(name: String, dark: Boolean) {
        mainClock.advanceTimeBy(400)
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "$name-${if (dark) "dark" else "light"}.png"))
    }

    @Test
    fun theVideoRingOffersDeclineAcceptVoiceAndAcceptVideoAndEachButtonFiresItsOwnAction() {
        for (dark in listOf(false, true)) runDesktopComposeUiTest(360, 200) {
            val fired = mutableListOf<String>()
            showRing(video = true, dark = dark, onVoice = { fired += "voice" }, onVideo = { fired += "video" }, onDecline = { fired += "decline" })
            onNodeWithText("Decline").performClick()
            onNodeWithText("Accept voice").performClick()
            onNodeWithText("Accept video").performClick()
            assertEquals(listOf("decline", "voice", "video"), fired)
            shoot("ring-window-video", dark)
        }
    }

    @Test
    fun theVoiceRingHasNoVideoButton() {
        for (dark in listOf(false, true)) runDesktopComposeUiTest(360, 200) {
            showRing(video = false, dark = dark)
            onNodeWithText("Accept voice").assertExists()
            assertTrue(onAllNodesWithTextCount("Accept video") == 0)
            shoot("ring-window-voice", dark)
        }
    }

    private fun ComposeUiTest.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    @Test
    fun trayMenuItemsRenderInATestHost() {
        for (dark in listOf(false, true)) runDesktopComposeUiTest(300, 150) {
            setContent {
                HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp)) {
                        callTrayItems(CallUiState(phase = CallPhase.Connected, muted = true), "Samwise Gamgee", "01:23").forEach { item ->
                            val label = when (item) {
                                is CallTrayItem.Status -> stringResource(MR.string.desktop_tray_in_call_timed, item.name, item.elapsed.orEmpty())
                                is CallTrayItem.Mute -> stringResource(if (item.muted) MR.string.desktop_menu_call_unmute else MR.string.desktop_menu_call_mute)
                                CallTrayItem.HangUp -> stringResource(MR.string.desktop_menu_call_hang_up)
                            }
                            Text(label, Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            onNodeWithText("In call with Samwise Gamgee · 01:23").assertExists()
            onNodeWithText("Unmute").assertExists()
            onNodeWithText("Hang Up").assertExists()
            shoot("tray-menu-items", dark)
        }
    }
}
