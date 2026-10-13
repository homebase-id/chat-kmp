package id.homebase.chat.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.click
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import id.homebase.chat.widget.bottomSpacing
import id.homebase.chat.widget.messageBubbleShape
import id.homebase.chat.widget.topSpacing
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasStateDescription
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
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import id.homebase.chat.services.calling.AudioRoute
import id.homebase.chat.services.calling.CallDirection
import id.homebase.chat.services.calling.CallEndReason
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallQuality
import id.homebase.chat.services.calling.CallVideos
import id.homebase.chat.services.calling.FailReason
import id.homebase.chat.services.calling.FakeVideo
import id.homebase.chat.services.calling.RemoteVideoSize
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
        videos = if (video) CallVideos(FakeVideo("FB"), FakeVideo("SG")) else CallVideos(),
        connectedAtMs = System.currentTimeMillis() - 83_000,
        verificationCode = "123 456",
    )

    private class Shot(val name: String, val marker: (video: Boolean) -> String, val state: (CallUiState) -> CallUiState)

    private val shots = listOf(
        Shot("outgoing", { "End call" }) { it.copy(phase = CallPhase.Outgoing, peerRinging = true, connectedAtMs = null, verificationCode = null) },
        Shot("incoming", { if (it) "Accept video call from" else "Accept voice call from" }) {
            it.copy(phase = CallPhase.Incoming, connectedAtMs = null, verificationCode = null)
        },
        Shot("connecting", { "End call" }) { it.copy(phase = CallPhase.Connecting, connectedAtMs = null, verificationCode = null) },
        Shot("connected", { if (it) "Turn camera off" else "Mute microphone" }) { it.copy(phase = CallPhase.Connected) },
        Shot("reconnecting", { "End call" }) { it.copy(phase = CallPhase.Reconnecting) },
        Shot("muted", { "Unmute microphone" }) { it.copy(phase = CallPhase.Connected, muted = true, route = AudioRoute.Speaker, quality = CallQuality.Poor) },
        Shot("peercamoff", { "End call" }) { it.copy(phase = CallPhase.Connected, remoteVideo = false) },
        Shot("selfcamoff", { "End call" }) { it.copy(phase = CallPhase.Connected, localVideo = false, route = AudioRoute.Bluetooth) },
        Shot("failed", { "Retry" }) { it.copy(phase = CallPhase.Ended(CallEndReason.Failed(FailReason.NoRoute)), verificationCode = null) },
        Shot("ended", { "Call back" }) {
            it.copy(phase = CallPhase.Ended(CallEndReason.Hangup(remote = true)), endedAtMs = it.connectedAtMs!! + 754_000)
        },
    )

    private fun androidx.compose.ui.test.ComposeUiTest.show(
        dark: Boolean,
        state: CallUiState,
        recorder: Recorder = Recorder(),
        fontScale: Float = 1f,
    ) {
        // Paused before composition so finite animations (the ended countdown) are captured mid-flight, not settled.
        mainClock.autoAdvance = false
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
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
        }
        mainClock.advanceTimeBy(900)
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
        onNodeWithContentDescription("Accept voice call from Samwise Gamgee").performClick()
        onNodeWithContentDescription("Accept video call from Samwise Gamgee").performClick()
        onNodeWithContentDescription("Decline call from Samwise Gamgee").performClick()
        assertEquals(listOf("acceptAudio", "acceptVideo", "decline"), recorder.calls)
    }

    @Test
    fun incomingCallWrapsAtDoubleFontScaleWithoutClippingTheActions() = runDesktopComposeUiTest(390, 844) {
        show(false, shots.first { it.name == "incoming" }.state(base(true, Size.Phone)), fontScale = 2f)
        val decline = onNodeWithContentDescription("Decline call from", substring = true).getBoundsInRoot()
        val video = onNodeWithContentDescription("Accept video call from", substring = true).getBoundsInRoot()
        assertTrue(decline.left.value >= 0 && video.right.value <= 390, "actions fit: $decline $video")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "incoming-fontscale2-light-video-phone.png"))
    }

    @Test
    fun togglesHighlightOnlyTheNonDefaultStateAndAnnounceIt() = runDesktopComposeUiTest(390, 844) {
        show(false, base(true, Size.Phone).copy(phase = CallPhase.Connected, muted = true, localVideo = false))
        onNode(hasContentDescription("Unmute microphone") and hasStateDescription("Muted")).assertExists()
        onNode(hasContentDescription("Turn camera on") and hasStateDescription("Camera off")).assertExists()
        onNode(hasContentDescription("Choose audio output") and hasStateDescription("Earpiece")).assertExists()
    }

    @Test
    fun reconnectingVideoCallSaysSoOnce() = runDesktopComposeUiTest(390, 844) {
        show(false, shots.first { it.name == "reconnecting" }.state(base(true, Size.Phone)))
        val said = onAllNodes(hasText("Reconnecting", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, said.size, "reconnecting status shown ${said.size} times")
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
    fun phoneLandscapeKeepsNameControlsAndSelfViewOnScreenAndApart() {
        val width = 844
        val height = 390
        val cases = listOf(
            "incoming" to false, "incoming" to true, "outgoing" to false, "outgoing" to true,
            "connected" to false, "connected" to true, "ended" to false, "failed" to false,
        )
        for ((name, video) in cases) runDesktopComposeUiTest(width, height) {
            show(false, shots.first { it.name == name }.state(base(video, Size.Phone)))
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "$name-landscape-${if (video) "video" else "audio"}-phone.png"))
            val buttons = onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
            val names = onAllNodes(hasText("Samwise Gamgee"), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
            val preview = onAllNodes(hasContentDescription("Your camera preview", substring = true)).fetchSemanticsNodes().map { it.boundsInRoot }
            // boundsInRoot is clipped by ancestors, so a name pushed out of its container reports zero height.
            assertTrue(names.isNotEmpty() && names.all { it.height > 12f }, "$name video=$video: peer name visible $names")
            assertTrue(buttons.none { it.height < 40f }, "$name video=$video: a control is clipped $buttons")
            for (r in buttons + names + preview) {
                assertTrue(r.left >= 0f && r.top >= 0f && r.right <= width && r.bottom <= height, "$name video=$video: $r off screen")
            }
            for (n in names) for (b in buttons) assertTrue(!n.overlaps(b), "$name video=$video: name $n under control $b")
            for (p in preview.filter { it.width < width }) {
                assertTrue(p.width > p.height, "$name: self-view follows the landscape camera: $p")
                for (b in buttons) assertTrue(!p.overlaps(b), "$name: self-view $p covers control $b")
            }
        }
    }

    @Test
    fun landscapeSelfViewSnapsToABottomCornerBelowTheTopOne() = runDesktopComposeUiTest(844, 390) {
        show(false, shots.first { it.name == "connected" }.state(base(true, Size.Phone)))
        val preview = onNodeWithContentDescription("Your camera preview", substring = true)
        val start = preview.getBoundsInRoot()
        preview.performTouchInput { swipe(center, center + Offset(-600f, 300f), durationMillis = 200) }
        mainClock.advanceTimeBy(1_500)
        val end = onNodeWithContentDescription("Your camera preview", substring = true).getBoundsInRoot()
        assertTrue(end.top > start.top && end.bottom.value <= 390f, "bottom corner sits below the top one and on screen: $start -> $end")
        val buttons = onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        val endRect = androidx.compose.ui.geometry.Rect(end.left.value, end.top.value, end.right.value, end.bottom.value)
        for (b in buttons) assertTrue(!endRect.overlaps(b), "snapped self-view $endRect covers control $b")
    }

    @Test
    fun outgoingVideoFillsTheStageWithOurCameraAndShrinksIntoTheSelfViewOnConnect() {
        for (size in Size.entries) for (remoteVideo in listOf(true, false)) for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(size.width, size.height) {
                val ringing = shots.first { it.name == "outgoing" }.state(base(true, size))
                var state by androidx.compose.runtime.mutableStateOf(ringing)
                mainClock.autoAdvance = false
                setContent {
                    HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                        CallScreen(ui = state, peerName = "Samwise Gamgee", avatar = {}, actions = Recorder().actions())
                    }
                }
                mainClock.advanceTimeBy(900)
                val tag = "${if (dark) "dark" else "light"}-${if (remoteVideo) "video" else "peercamoff"}-${size.name.lowercase()}"
                val full = previewBounds()
                if (size == Size.Phone) {
                    assertEquals(0f, full.left.value, 1f)
                    assertEquals(0f, full.top.value, 1f)
                    assertEquals(size.width.toFloat(), full.width.value, 1f)
                    assertEquals(size.height.toFloat(), full.height.value, 1f)
                } else {
                    assertTrue(full.width.value > 900f && full.height.value > 600f, "fills the desktop stage: $full")
                }
                assertShown("Ringing")
                assertShown("End call")
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "dialing-fullpreview-$tag.png"))

                state = state.copy(phase = CallPhase.Connecting)
                mainClock.advanceTimeBy(900)
                assertEquals(full, previewBounds(), "still fills while connecting")

                state = state.copy(phase = CallPhase.Connected, connectedAtMs = System.currentTimeMillis(), remoteVideo = remoteVideo)
                mainClock.advanceTimeBy(160)
                val mid = previewBounds()
                assertTrue(mid.width < full.width && mid.width.value > 108f, "mid-shrink: $mid")
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "dialing-shrinking-$tag.png"))
                mainClock.advanceTimeBy(1_000)
                val tile = previewBounds()
                assertEquals(if (size == Size.Phone) 108f else 192f, tile.width.value, 1f, "settles into the self-view")
                ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "dialing-shrunk-$tag.png"))
            }
        }
    }

    @Test
    fun voiceCallsAndAnsweredVideoCallsKeepTheirLayout() {
        runDesktopComposeUiTest(390, 844) {
            show(false, shots.first { it.name == "outgoing" }.state(base(false, Size.Phone)))
            assertTrue(onAllNodes(hasContentDescription("Your camera preview", substring = true)).fetchSemanticsNodes().isEmpty())
        }
        runDesktopComposeUiTest(390, 844) {
            show(false, shots.first { it.name == "outgoing" }.state(base(true, Size.Phone)).copy(localVideo = false))
            assertTrue(onAllNodes(hasContentDescription("Your camera preview", substring = true)).fetchSemanticsNodes().isEmpty())
        }
        runDesktopComposeUiTest(390, 844) {
            show(false, shots.first { it.name == "connecting" }.state(base(true, Size.Phone)).copy(direction = CallDirection.Incoming))
            assertEquals(108f, previewBounds().width.value, 1f, "the callee's camera starts as the self-view")
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.clickables() =
        onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }

    private fun androidx.compose.ui.test.ComposeUiTest.previewBounds() =
        onNodeWithContentDescription("Your camera preview", substring = true).getBoundsInRoot()

    private fun androidx.compose.ui.test.ComposeUiTest.controlsShown() =
        onAllNodes(hasContentDescription("Mute microphone"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun videoControlsFadeAfterIdleAndATapOnTheVideoBringsThemBack() {
        for (dark in listOf(false, true)) runDesktopComposeUiTest(390, 844) {
            show(dark, shots.first { it.name == "connected" }.state(base(true, Size.Phone)))
            val theme = if (dark) "dark" else "light"
            assertTrue(controlsShown())
            val shownTop = previewBounds().top.value
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "chrome-shown-$theme-video-phone.png"))

            mainClock.advanceTimeBy(ChromeIdleMs + 1_500)
            assertTrue(!controlsShown(), "controls fade after ${ChromeIdleMs}ms")
            assertTrue(clickables().isEmpty(), "nothing hidden is still tappable: ${clickables()}")
            val hiddenTop = previewBounds().top.value
            assertEquals(16f, hiddenTop, 1f, "self-view rises into the freed top")
            assertTrue(hiddenTop < shownTop)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "chrome-hidden-$theme-video-phone.png"))

            onRoot().performTouchInput { click(Offset(195f, 500f)) }
            mainClock.advanceTimeBy(1_000)
            assertTrue(controlsShown(), "a tap brings them back")
            assertEquals(shownTop, previewBounds().top.value, 1f, "and the self-view steps back below the top bar")
        }
    }

    @Test
    fun tappingAControlKeepsTheControlsAndRestartsTheirTimer() = runDesktopComposeUiTest(390, 844) {
        val recorder = Recorder()
        show(false, shots.first { it.name == "connected" }.state(base(true, Size.Phone)), recorder)
        mainClock.advanceTimeBy(ChromeIdleMs - 1_000)
        onNodeWithContentDescription("Mute microphone").performClick()
        mainClock.advanceTimeBy(ChromeIdleMs - 500)
        assertTrue(controlsShown(), "the press pushed the fade back and did not toggle the controls away")
        assertEquals(listOf("mute"), recorder.calls)
    }

    @Test
    fun anOpenMenuOrDialogHoldsTheControls() {
        runDesktopComposeUiTest(390, 844) {
            show(false, shots.first { it.name == "connected" }.state(base(true, Size.Phone)))
            onNodeWithContentDescription("Choose audio output").performClick()
            mainClock.advanceTimeBy(ChromeIdleMs * 3)
            assertTrue(controlsShown(), "route menu holds the controls")
            assertTrue(onAllNodes(hasText("Speaker"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
        runDesktopComposeUiTest(390, 844) {
            mainClock.autoAdvance = false
            setContent {
                HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                    CallScreen(
                        ui = shots.first { it.name == "connected" }.state(base(true, Size.Phone)),
                        peerName = "Samwise Gamgee",
                        avatar = {},
                        actions = Recorder().actions(),
                        chromeHeld = true,
                    )
                }
            }
            mainClock.advanceTimeBy(ChromeIdleMs * 3)
            assertTrue(controlsShown(), "a dialog over the call holds the controls")
        }
    }

    @Test
    fun voiceAndRingingCallsNeverHideTheirControls() {
        val cases = listOf(
            shots.first { it.name == "connected" }.state(base(false, Size.Phone)),
            shots.first { it.name == "reconnecting" }.state(base(true, Size.Phone)),
            shots.first { it.name == "connecting" }.state(base(true, Size.Phone)),
        )
        for (state in cases) runDesktopComposeUiTest(390, 844) {
            show(false, state)
            mainClock.advanceTimeBy(ChromeIdleMs * 2)
            assertShown("End call")
        }
    }

    @Test
    fun selfViewSitsAboveTheDockAndDropsToTheEdgeWhenTheControlsHide() = runDesktopComposeUiTest(390, 844) {
        show(false, shots.first { it.name == "connected" }.state(base(true, Size.Phone)))
        onNodeWithContentDescription("Your camera preview", substring = true).performTouchInput { swipe(center, center + Offset(0f, 600f), durationMillis = 200) }
        mainClock.advanceTimeBy(1_500)
        val shown = previewBounds()
        val rect = androidx.compose.ui.geometry.Rect(shown.left.value, shown.top.value, shown.right.value, shown.bottom.value)
        for (b in clickables()) assertTrue(!rect.overlaps(b), "self-view $rect covers control $b")
        assertTrue(844f - shown.bottom.value >= 214f, "self-view clears the dock: $shown")
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "selfview-bottom-shown-light-video-phone.png"))

        mainClock.advanceTimeBy(ChromeIdleMs + 1_500)
        val hidden = previewBounds()
        assertEquals(844f - 16f, hidden.bottom.value, 1f, "self-view drops into the freed space")
        assertEquals(390f - 16f, hidden.right.value, 1f)
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "selfview-bottom-hidden-light-video-phone.png"))
    }

    @Test
    fun pictureInPictureShowsTheRemoteVideoWithASelfInset() {
        for ((w, h) in listOf(180 to 320, 320 to 180)) runDesktopComposeUiTest(w, h) {
            val state = shots.first { it.name == "connected" }.state(base(true, Size.Phone))
            setContent {
                HomebaseTheme(darkTheme = true, followsSystemTheme = false) {
                    CallPipLayout(state) {}
                }
            }
            val self = onNodeWithContentDescription("You").getBoundsInRoot()
            assertTrue(self.right.value <= w && self.bottom.value <= h && self.left.value > w / 2f && self.top.value > h / 3f, "inset bottom-end: $self")
            assertTrue(self.right.value - self.left.value < w / 2f, "inset stays small: $self")
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "pip-${w}x$h.png"))
        }
        runDesktopComposeUiTest(180, 320) {
            setContent {
                HomebaseTheme(darkTheme = true, followsSystemTheme = false) {
                    CallPipLayout(shots.first { it.name == "selfcamoff" }.state(base(true, Size.Phone))) {}
                }
            }
            assertTrue(onAllNodes(hasContentDescription("You")).fetchSemanticsNodes().isEmpty(), "no inset with the camera off")
        }
    }

    @Test
    fun aTapJustAfterTheCallScreenLeavesDoesNotReachTheAppUnderneath() = runDesktopComposeUiTest(390, 844) {
        var under = 0
        var visible by androidx.compose.runtime.mutableStateOf(true)
        mainClock.autoAdvance = false
        setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().clickable { under++ })
                DismissTapShield(visible)
            }
        }
        mainClock.advanceTimeBy(100)
        onRoot().performTouchInput { click(center) }
        assertEquals(1, under, "nothing shields while the call screen is up")
        visible = false
        mainClock.advanceTimeBy(100)
        onRoot().performTouchInput { click(center) }
        assertEquals(1, under, "a tap right after it left is swallowed")
        mainClock.advanceTimeBy(TapShieldMs)
        onRoot().performTouchInput { click(center) }
        assertEquals(2, under, "and the app answers again after the beat")
    }

    @Test
    fun remoteVideoLetterboxesOnlyWhenItsOrientationDiffersFromTheScreen() {
        fun remoteBounds(width: Int, height: Int, video: Pair<Int, Int>?): androidx.compose.ui.geometry.Rect {
            var rect = androidx.compose.ui.geometry.Rect.Zero
            RemoteVideoSize.current.value = video
            try {
                runDesktopComposeUiTest(width, height) {
                    show(false, shots.first { it.name == "connected" }.state(base(true, Size.Phone)))
                    rect = onAllNodes(hasTestTag(RemoteVideoTag), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot
                }
            } finally {
                RemoteVideoSize.current.value = null
            }
            return rect
        }
        val landscapeOnPortrait = remoteBounds(390, 844, 1280 to 720)
        assertEquals(390f, landscapeOnPortrait.width, 1f)
        assertEquals(390f * 720 / 1280, landscapeOnPortrait.height, 2f)
        assertEquals(844f / 2, landscapeOnPortrait.center.y, 2f)
        val portraitOnLandscape = remoteBounds(844, 390, 720 to 1280)
        assertEquals(390f, portraitOnLandscape.height, 1f)
        assertEquals(390f * 720 / 1280, portraitOnLandscape.width, 2f)
        assertEquals(844f, remoteBounds(390, 844, 720 to 1280).height, 1f)
        assertEquals(844f, remoteBounds(844, 390, 1280 to 720).width, 1f)
        assertEquals(844f, remoteBounds(390, 844, null).height, 1f)
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

    @Test
    fun callEventBubblesSitAmongChatBubblesInBothThemes() {
        val rows: List<Pair<Any?, Boolean>> = listOf(
            "Are you free to talk about the trip?" to false,
            CallEventDescriptor(CallOutcome.Completed, durationSeconds = 754) to false,
            CallEventDescriptor(CallOutcome.Completed, video = true, durationSeconds = 3_725) to true,
            "Sorry, signal dropped" to true,
            CallEventDescriptor(CallOutcome.NoAnswer) to false,
            CallEventDescriptor(CallOutcome.NoAnswer, video = true) to true,
            CallEventDescriptor(CallOutcome.Cancelled) to true,
            CallEventDescriptor(CallOutcome.Declined) to true,
            CallEventDescriptor(CallOutcome.Busy, video = true) to true,
            CallEventDescriptor(CallOutcome.Failed) to false,
            null to false,
        )
        class Variant(val name: String, val width: Int, val dark: Boolean, val fontScale: Float = 1f, val rtl: Boolean = false)
        val variants = listOf(
            Variant("light-phone", 390, false), Variant("dark-phone", 390, true),
            Variant("light-desktop", 900, false), Variant("dark-desktop", 900, true),
            Variant("light-phone-font150", 390, false, fontScale = 1.5f), Variant("dark-phone-rtl", 390, true, rtl = true),
        )
        for (v in variants) runDesktopComposeUiTest(v.width, if (v.fontScale > 1f) 1300 else 980) {
            setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, v.fontScale),
                    LocalLayoutDirection provides if (v.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    HomebaseTheme(darkTheme = v.dark, followsSystemTheme = false) {
                        val positions = clusterPositions(rows.map { it.second })
                        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(12.dp)) {
                            for ((index, row) in rows.withIndex()) {
                                val (item, own) = row
                                val container = if (own) HomebaseTheme.extendedColors.bubbleSentSurface else MaterialTheme.colorScheme.surfaceContainerHigh
                                val content = if (own) HomebaseTheme.extendedColors.bubbleSentOnSurface else MaterialTheme.colorScheme.onSurface
                                val position = positions[index]
                                val shape = messageBubbleShape(own, position)
                                val placed = Modifier.align(if (own) Alignment.End else Alignment.Start)
                                    .padding(top = position.topSpacing(), bottom = position.bottomSpacing())
                                    .widthIn(max = 320.dp)
                                if (item is String) {
                                    Surface(placed, shape = shape, color = container, contentColor = content) {
                                        Text(
                                            item,
                                            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                            style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                                        )
                                    }
                                } else {
                                    val descriptor = item as CallEventDescriptor?
                                    CallEventBubble(
                                        descriptor = descriptor,
                                        isOwn = own,
                                        modifier = placed,
                                        shape = shape,
                                        containerColor = container,
                                        contentColor = content,
                                        onCallBack = descriptor?.let { {} },
                                        footer = { color -> Text("12:41", style = MaterialTheme.typography.labelSmall, color = color) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            assertShown("Missed call")
            assertShown("12\u00A0min\u00A034\u00A0s")
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "bubbles-${v.name}.png"))
        }
    }
}
