package id.homebase.chat.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallPhase
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun theVoiceRingOffersTheSameThreeButtonsUntruncated() {
        for (dark in listOf(false, true)) runDesktopComposeUiTest(360, 200) {
            showRing(video = false, dark = dark)
            mainClock.advanceTimeBy(400)
            for (label in listOf("Accept voice", "Accept video", "Decline")) {
                val node = onNodeWithText(label)
                node.assertExists()
                val layouts = mutableListOf<TextLayoutResult>()
                node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                val layout = layouts.single()
                assertEquals(label.length, layout.getLineEnd(0, visibleEnd = true), "$label is truncated")
            }
            shoot("ring-window-voice", dark)
        }
    }
}
