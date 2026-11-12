package id.homebase.chat.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.width
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

private const val RingTag = "ring"

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

    private fun ComposeUiTest.showRing(
        video: Boolean,
        dark: Boolean,
        onAccept: () -> Unit = {},
        onDecline: () -> Unit = {},
        shape: Shape = RectangleShape,
        fontScale: Float = 1f,
    ) {
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                Box(Modifier.width(RingWindowWidth)) {
                    CallRingContent(
                        video = video,
                        name = "Samwise Gamgee",
                        avatar = { size ->
                            Box(Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                                Text("SG", color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        },
                        onAccept = onAccept,
                        onDecline = onDecline,
                        shape = shape,
                        modifier = Modifier.testTag(RingTag),
                    )
                }
            }
            }
        }
    }

    private fun ComposeUiTest.shoot(name: String, dark: Boolean) {
        mainClock.advanceTimeBy(400)
        ImageIO.write(onNodeWithTag(RingTag).captureToImage().toAwtImage(), "png", File(outDir, "$name-${if (dark) "dark" else "light"}.png"))
    }

    @Test
    fun theVideoRingOffersOnlyDeclineAndAcceptWithVideo() {
        for (dark in listOf(false, true)) runDesktopComposeUiTest(360, 200) {
            val fired = mutableListOf<String>()
            showRing(video = true, dark = dark, onAccept = { fired += "accept" }, onDecline = { fired += "decline" })
            onNodeWithText("Decline").performClick()
            onNodeWithContentDescription("Accept video call from Samwise Gamgee (Ctrl+Shift+A)").performClick()
            assertEquals(listOf("decline", "accept"), fired)
            onNodeWithText("Accept voice").assertDoesNotExist()
            onNodeWithText("Voice only").assertDoesNotExist()
            shoot("ring-window-video", dark)
        }
    }

    @Test
    fun theVoiceRingOffersDeclineAndAcceptUntruncated() {
        for (dark in listOf(false, true)) runDesktopComposeUiTest(360, 200) {
            showRing(video = false, dark = dark)
            mainClock.advanceTimeBy(400)
            onNodeWithContentDescription("Accept voice call from Samwise Gamgee (Ctrl+Shift+A)").assertExists()
            onNodeWithText("Accept video").assertDoesNotExist()
            for (label in listOf("Accept", "Decline")) {
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

    @Test
    fun theRoundedRingDeclinesWithTheCallersNameForScreenReaders() {
        for (dark in listOf(false, true)) runDesktopComposeUiTest(360, 200) {
            showRing(video = true, dark = dark, shape = RoundedCornerShape(12.dp))
            onNodeWithContentDescription("Decline call from Samwise Gamgee (Ctrl+Shift+H)").assertExists()
            onNodeWithContentDescription("Accept video call from Samwise Gamgee (Ctrl+Shift+A)").assertExists()
            shoot("ring-window-rounded", dark)
        }
    }

    @Test
    fun atLargeFontsTheRingGrowsTallerAndEveryLabelKeepsTheSameSize() {
        for (scale in listOf(1.3f, 2f)) for (dark in listOf(false, true)) runDesktopComposeUiTest(360, 520) {
            showRing(video = false, dark = dark, shape = RoundedCornerShape(12.dp), fontScale = scale)
            shoot("ring-window-font${(scale * 100).toInt()}", dark)
            val sizes = listOf("Decline", "Accept").map { label ->
                val layouts = mutableListOf<TextLayoutResult>()
                onNodeWithText(label).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
                val layout = layouts.single()
                assertEquals(label.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true), "$label is cut at $scale")
                layout.layoutInput.style.fontSize
            }
            assertEquals(1, sizes.distinct().size, "labels differ in size at $scale: $sizes")
        }
    }
}
