package id.homebase.chat.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import id.homebase.chat.services.calling.AudioRoute
import id.homebase.chat.services.calling.CallDirection
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallVideos
import id.homebase.chat.services.calling.FailReason
import id.homebase.chat.services.calling.FakeVideo
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CallScreenshotTest {
    private enum class Size(val width: Int, val height: Int, val routes: List<AudioRoute>) {
        Phone(390, 844, listOf(AudioRoute.Earpiece, AudioRoute.Speaker, AudioRoute.Bluetooth)),
        Desktop(1280, 800, emptyList()),
    }

    private class Recorder {
        val calls = mutableListOf<String>()
        fun actions() = CallScreenActions(
            acceptAudio = { calls += "acceptAudio" },
            acceptVideo = { calls += "acceptVideo" },
            decline = { calls += "decline" },
            hangup = { calls += "hangup" },
            toggleMute = { calls += "mute" },
            toggleVideo = { calls += "video" },
            switchCamera = { calls += "flip" },
            setRoute = { calls += "route:$it" },
            onVerificationClick = { calls += "verify" },
            retry = { calls += "retry" },
            close = { calls += "close" },
            minimize = { calls += "minimize" },
            endFromKeyboard = { calls += "end" },
        )
    }

    private val outDir = File("build/call-shots").apply { mkdirs() }

    private fun base(video: Boolean, size: Size) = CallUiState(
        callId = "shot",
        peer = "samwise.gamgee.demo.rocks",
        direction = CallDirection.Outgoing,
        localVideo = video,
        remoteVideo = video,
        availableRoutes = size.routes,
        videos = if (video) CallVideos(FakeVideo(0xFF5C6BC0, "FB"), FakeVideo(0xFF26A69A, "SG")) else CallVideos(),
        connectedAtMs = System.currentTimeMillis() - 83_000,
        verificationCode = "123 456",
    )

    private class Shot(val name: String, val marker: (video: Boolean) -> String, val state: (CallUiState) -> CallUiState)

    private val shots = listOf(
        Shot("outgoing", { "End call" }) { it.copy(phase = CallPhase.Outgoing, peerRinging = true, connectedAtMs = null, verificationCode = null) },
        Shot("incoming", { if (it) "Accept as video call" else "Accept as voice call" }) {
            it.copy(phase = CallPhase.Incoming, direction = CallDirection.Incoming, connectedAtMs = null, verificationCode = null)
        },
        Shot("connecting", { "End call" }) { it.copy(phase = CallPhase.Connecting, connectedAtMs = null, verificationCode = null) },
        Shot("connected", { if (it) "Turn camera off" else "Mute microphone" }) { it.copy(phase = CallPhase.Connected) },
        Shot("reconnecting", { "End call" }) { it.copy(phase = CallPhase.Reconnecting) },
        Shot("failed", { "Retry" }) { it.copy(phase = CallPhase.Ended(CallEndReason.Failed(FailReason.NoRoute)), verificationCode = null) },
        Shot("ended", { "Call ended" }) { it.copy(phase = CallPhase.Ended(CallEndReason.Hangup(remote = true))) },
    )

    private fun androidx.compose.ui.test.ComposeUiTest.show(dark: Boolean, state: CallUiState, recorder: Recorder = Recorder()) {
        setContent {
            HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                CallScreen(
                    ui = state,
                    peerName = "Samwise Gamgee",
                    avatar = { size ->
                        Box(Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                            Text("SG", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    },
                    actions = recorder.actions(),
                )
            }
        }
        mainClock.autoAdvance = false
        mainClock.advanceTimeBy(600)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.assertShown(marker: String) {
        val matches = onAllNodes(hasContentDescription(marker, substring = true) or hasText(marker, substring = true), useUnmergedTree = true)
        assertTrue(matches.fetchSemanticsNodes().isNotEmpty(), "no node mentions '$marker'")
    }

    @Test
    fun everyStateRendersInBothThemesMediaKindsAndWidths() {
        var written = 0
        for (shot in shots) for (size in Size.entries) for (video in listOf(false, true)) for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(size.width, size.height) {
                show(dark, shot.state(base(video, size)))
                assertShown(shot.marker(video))
                val image = onRoot().captureToImage().toAwtImage()
                val colors = HashSet<Int>()
                for (x in 0 until image.width step 7) for (y in 0 until image.height step 7) colors += image.getRGB(x, y)
                assertTrue(colors.size > 12, "${shot.name} rendered blank")
                val file = File(outDir, "${shot.name}-${if (dark) "dark" else "light"}-${if (video) "video" else "audio"}-${size.name.lowercase()}.png")
                ImageIO.write(image, "png", file)
                written++
            }
        }
        assertEquals(shots.size * 8, written)
        assertEquals(written, outDir.listFiles { f -> f.name.matches(Regex("(${shots.joinToString("|") { it.name }})-(light|dark)-(audio|video)-(phone|desktop)\\.png")) }.size)
    }

    @Test
    fun failedStateExplainsBlockedNetworksAndOffersRetryAndClose() = runDesktopComposeUiTest(390, 844) {
        val recorder = Recorder()
        show(false, shots.first { it.name == "failed" }.state(base(false, Size.Phone)), recorder)
        assertShown("Can't connect")
        assertShown("blocking direct calls")
        onNodeWithText("Retry").performClick()
        onNodeWithText("Close").performClick()
        assertEquals(listOf("retry", "close"), recorder.calls)
    }

    @Test
    fun incomingVideoCallOffersSeparateAudioAndVideoAcceptAndDecline() = runDesktopComposeUiTest(390, 844) {
        val recorder = Recorder()
        show(false, shots.first { it.name == "incoming" }.state(base(true, Size.Phone)), recorder)
        onNodeWithContentDescription("Accept as voice call").performClick()
        onNodeWithContentDescription("Accept as video call").performClick()
        onNodeWithContentDescription("Decline").performClick()
        assertEquals(listOf("acceptAudio", "acceptVideo", "decline"), recorder.calls)
    }

    @Test
    fun localPreviewSnapsToTheNearestCornerOnRelease() = runDesktopComposeUiTest(390, 844) {
        show(false, shots.first { it.name == "connected" }.state(base(true, Size.Phone)))
        val preview = onNodeWithContentDescription("Your camera preview", substring = true)
        val start = preview.getBoundsInRoot()
        assertTrue(start.left.value > 195 && start.top.value < 200, "starts top end: $start")

        preview.performTouchInput { swipe(center, center + Offset(-250f, 480f), durationMillis = 200) }
        mainClock.advanceTimeBy(1_500)
        val end = onNodeWithContentDescription("Your camera preview", substring = true).getBoundsInRoot()
        assertEquals(16f, end.left.value, 1f)
        assertTrue(end.top.value > 300, "snapped to a bottom corner: $end")
    }

    @Test
    fun desktopUsesAFloatingToolbarWhilePhoneStacksTheEndButtonBelow() {
        fun layout(size: Size): Pair<Float, Float> {
            var pair = 0f to 0f
            runDesktopComposeUiTest(size.width, size.height) {
                show(false, shots.first { it.name == "connected" }.state(base(false, size)))
                val mute = onNodeWithContentDescription("Mute microphone").getBoundsInRoot()
                val end = onNodeWithContentDescription("End call").getBoundsInRoot()
                pair = mute.top.value to end.top.value
            }
            return pair
        }
        val (phoneMute, phoneEnd) = layout(Size.Phone)
        assertTrue(phoneEnd > phoneMute + 40, "phone stacks the end button under the toggles")
        val (deskMute, deskEnd) = layout(Size.Desktop)
        assertEquals(deskMute, deskEnd, 20f)
    }

    @Test
    fun keyboardShortcutsMapToCallActions() = runDesktopComposeUiTest(1280, 800) {
        val recorder = Recorder()
        show(false, shots.first { it.name == "connected" }.state(base(true, Size.Desktop)), recorder)
        onRoot().performKeyInput { pressKey(Key.M) }
        onRoot().performKeyInput { pressKey(Key.V) }
        onRoot().performKeyInput { pressKey(Key.Escape) }
        onRoot().performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.W)
            keyUp(Key.CtrlLeft)
        }
        assertEquals(listOf("mute", "video", "end", "end"), recorder.calls)
    }
}
