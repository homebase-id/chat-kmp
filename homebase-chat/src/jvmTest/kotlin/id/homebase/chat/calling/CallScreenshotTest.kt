package id.homebase.chat.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onLast
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
            accept = { calls += "accept" },
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
            reportProblem = { calls += "report" },
        )
    }

    private val chatNames = listOf("Merry Brandybuck", "Pippin Took", "Rosie Cotton", "Bilbo Baggins")
    private val chatPreviews = listOf("See you at the Green Dragon", "Second breakfast?", "The garden looks lovely", "Back in time for tea")

    private val outDir = File("build/call-shots").apply { mkdirs() }

    private fun shot(name: String, video: Boolean = true) = shots.first { it.name == name }.state(base(video, Size.Phone))

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
        chromeHeld: Boolean = false,
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
                    chromeHeld = chromeHeld,
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
        show(false, shot("failed", video = false), recorder)
        assertShown("Can't connect")
        assertShown("blocking direct calls")
        onNodeWithText("Retry").performClick()
        onNodeWithText("Close").performClick()
        assertEquals(listOf("retry", "close"), recorder.calls)
    }

    @Test
    fun phoneCallScreensWithoutATopBarStillShowTheBetaBadgeAndEndedOnesOfferAReport() {
        for (name in listOf("outgoing", "connected", "failed", "ended")) for (video in listOf(false, true)) {
            runDesktopComposeUiTest(390, 844) {
                val recorder = Recorder()
                show(false, shot(name, video), recorder, chromeHeld = true)
                assertShown("Beta")
                if (name == "failed" || name == "ended") {
                    onNodeWithText("Report a problem").performClick()
                    assertEquals(listOf("report"), recorder.calls, "$name video=$video")
                }
            }
        }
    }

    @Test
    fun incomingVideoCallOffersOnlyDeclineAndAcceptWithVideo() = runDesktopComposeUiTest(390, 844) {
        val recorder = Recorder()
        show(false, shot("incoming"), recorder)
        onNodeWithContentDescription("Accept voice call from Samwise Gamgee").assertDoesNotExist()
        onNodeWithText("Voice only").assertDoesNotExist()
        onNodeWithContentDescription("Accept video call from Samwise Gamgee").performClick()
        onNodeWithContentDescription("Decline call from Samwise Gamgee").performClick()
        assertEquals(listOf("accept", "decline"), recorder.calls)
    }

    @Test
    fun incomingCallWrapsAtDoubleFontScaleWithoutClippingTheActions() = runDesktopComposeUiTest(390, 844) {
        show(false, shot("incoming"), fontScale = 2f)
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
        show(false, shot("reconnecting"))
        val said = onAllNodes(hasText("Reconnecting", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, said.size, "reconnecting status shown ${said.size} times")
    }

    @Test
    fun localPreviewSnapsToTheNearestCornerOnRelease() = runDesktopComposeUiTest(390, 844) {
        show(false, shot("connected"))
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
            // The ButtonGroup's overflow indicator is composed for measuring but left unplaced while everything fits.
            val buttons = onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes()
                .filter { it.layoutInfo.isPlaced }.map { it.boundsInRoot }
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
        show(false, shot("connected"))
        val preview = onNodeWithContentDescription("Your camera preview", substring = true)
        val start = preview.getBoundsInRoot()
        preview.performTouchInput { swipe(center, center + Offset(-600f, 300f), durationMillis = 200) }
        mainClock.advanceTimeBy(1_500)
        val end = onNodeWithContentDescription("Your camera preview", substring = true).getBoundsInRoot()
        assertTrue(end.top > start.top && end.bottom.value <= 390f, "bottom corner sits below the top one and on screen: $start -> $end")
        val buttons = onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
        val endRect = Rect(end.left.value, end.top.value, end.right.value, end.bottom.value)
        for (b in buttons) assertTrue(!endRect.overlaps(b), "snapped self-view $endRect covers control $b")
    }

    @Test
    fun outgoingVideoFillsTheStageWithOurCameraAndShrinksIntoTheSelfViewOnConnect() {
        for (size in Size.entries) for (remoteVideo in listOf(true, false)) for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(size.width, size.height) {
                val ringing = shots.first { it.name == "outgoing" }.state(base(true, size))
                var state by mutableStateOf(ringing)
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
            show(false, shot("outgoing", video = false))
            assertTrue(onAllNodes(hasContentDescription("Your camera preview", substring = true)).fetchSemanticsNodes().isEmpty())
        }
        runDesktopComposeUiTest(390, 844) {
            show(false, shot("outgoing").copy(localVideo = false))
            assertTrue(onAllNodes(hasContentDescription("Your camera preview", substring = true)).fetchSemanticsNodes().isEmpty())
        }
        runDesktopComposeUiTest(390, 844) {
            show(false, shot("connecting").copy(direction = CallDirection.Incoming))
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
            show(dark, shot("connected"))
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
        show(false, shot("connected"), recorder)
        mainClock.advanceTimeBy(ChromeIdleMs - 1_000)
        onNodeWithContentDescription("Mute microphone").performClick()
        mainClock.advanceTimeBy(ChromeIdleMs - 500)
        assertTrue(controlsShown(), "the press pushed the fade back and did not toggle the controls away")
        assertEquals(listOf("mute"), recorder.calls)
    }

    @Test
    fun oneTapOnTheEncryptedShieldOpensTheVerificationSheet() = runDesktopComposeUiTest(390, 844) {
        val recorder = Recorder()
        show(false, shot("connected"), recorder)
        onNodeWithContentDescription("End-to-end encrypted, show verification code").performClick()
        assertEquals(listOf("verify"), recorder.calls)
        assertTrue(onAllNodes(hasText("123 456", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(), "no inline code chip")
    }

    @Test
    fun verificationSheetExplainsTheCodeAndCloses() {
        for (dark in listOf(false, true)) runDesktopComposeUiTest(390, 844) {
            var open by mutableStateOf(true)
            setContent {
                HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                    if (open) CallVerificationSheet("123 456") { open = false }
                }
            }
            waitForIdle()
            for (marker in listOf("End-to-end encrypted", "123 456", "Read the code aloud", "If the codes differ, end the call")) assertShown(marker)
            ImageIO.write(onAllNodes(isRoot()).onLast().captureToImage().toAwtImage(), "png", File(outDir, "verification-${if (dark) "dark" else "light"}.png"))
            onNodeWithText("Close").performClick()
            waitForIdle()
            assertTrue(!open, "Close dismisses the sheet")
        }
    }

    @Test
    fun anOpenMenuOrDialogHoldsTheControls() {
        runDesktopComposeUiTest(390, 844) {
            show(false, shot("connected"))
            onNodeWithContentDescription("Choose audio output").performClick()
            mainClock.advanceTimeBy(ChromeIdleMs * 3)
            assertTrue(controlsShown(), "route menu holds the controls")
            assertTrue(onAllNodes(hasText("Speaker"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
        runDesktopComposeUiTest(390, 844) {
            show(false, shot("connected"), chromeHeld = true)
            mainClock.advanceTimeBy(ChromeIdleMs * 3)
            assertTrue(controlsShown(), "a dialog over the call holds the controls")
        }
    }

    @Test
    fun voiceAndRingingCallsNeverHideTheirControls() {
        val cases = listOf(
            shot("connected", video = false),
            shot("reconnecting"),
            shot("connecting"),
        )
        for (state in cases) runDesktopComposeUiTest(390, 844) {
            show(false, state)
            mainClock.advanceTimeBy(ChromeIdleMs * 2)
            assertShown("End call")
        }
    }

    @Test
    fun selfViewSitsAboveTheDockAndDropsToTheEdgeWhenTheControlsHide() = runDesktopComposeUiTest(390, 844) {
        show(false, shot("connected"))
        onNodeWithContentDescription("Your camera preview", substring = true).performTouchInput { swipe(center, center + Offset(0f, 600f), durationMillis = 200) }
        mainClock.advanceTimeBy(1_500)
        val shown = previewBounds()
        val rect = Rect(shown.left.value, shown.top.value, shown.right.value, shown.bottom.value)
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
            val state = shot("connected")
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
                    CallPipLayout(shot("selfcamoff")) {}
                }
            }
            assertTrue(onAllNodes(hasContentDescription("You")).fetchSemanticsNodes().isEmpty(), "no inset with the camera off")
        }
    }

    @Test
    fun aPressOnTheEndedScreenThatOutlivesItsTimerNeverReachesTheAppUnderneath() = runDesktopComposeUiTest(390, 844) {
        val recorder = Recorder()
        var under = 0
        var state by mutableStateOf(shots.first { it.name == "ended" }.state(base(false, Size.Phone)))
        mainClock.autoAdvance = false
        setContent {
            HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().clickable { under++ })
                    CallLayer(visible = state.visible, surface = CallSurface.Full) {
                        CallScreen(ui = state, peerName = "Samwise Gamgee", avatar = {}, actions = recorder.actions())
                    }
                }
            }
        }
        mainClock.advanceTimeBy(1_000)
        val close = onNodeWithText("Close").getBoundsInRoot()
        val at = Offset(((close.left + close.right) / 2).value, ((close.top + close.bottom) / 2).value)
        onRoot().performTouchInput { down(at) }
        mainClock.advanceTimeBy(100)
        state = state.copy(dismissedCallId = state.callId)
        mainClock.advanceTimeBy(2_000)
        assertTrue(onAllNodes(hasText("Close")).fetchSemanticsNodes().isNotEmpty(), "the screen waits for the finger")
        onRoot().performTouchInput { up() }
        mainClock.advanceTimeBy(2_000)
        assertEquals(0, under, "the press finished on the call screen, not the app")
        assertTrue(onAllNodes(hasText("Close")).fetchSemanticsNodes().isEmpty(), "and the screen leaves once the finger lifts")
        onRoot().performTouchInput { click(at) }
        assertEquals(1, under, "a fresh tap reaches the app")
    }

    @Test
    fun aSecondTapAfterCloseReachesTheAppOnlyOnceTheCallScreenHasFullyLeft() {
        val seen = mutableListOf<Boolean>()
        for (gap in 0L..600L step 50L) runDesktopComposeUiTest(390, 844) {
            var under = 0
            var state by androidx.compose.runtime.mutableStateOf(shots.first { it.name == "ended" }.state(base(false, Size.Phone)))
            mainClock.autoAdvance = false
            val recorder = Recorder()
            val actions = recorder.actions()
            setContent {
                HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().clickable { under++ })
                        CallLayer(visible = state.visible, surface = CallSurface.Full) {
                            CallScreen(ui = state, peerName = "Samwise Gamgee", avatar = {}, actions = actions)
                        }
                    }
                }
            }
            mainClock.advanceTimeBy(1_000)
            val close = onNodeWithText("Close").getBoundsInRoot()
            val at = Offset(((close.left + close.right) / 2).value, ((close.top + close.bottom) / 2).value)
            onRoot().performTouchInput { click(at) }
            assertEquals(listOf("close"), recorder.calls)
            state = state.copy(dismissedCallId = state.callId)
            mainClock.advanceTimeBy(gap)
            val onScreen = onAllNodes(hasText("Close")).fetchSemanticsNodes().isNotEmpty()
            onRoot().performTouchInput { click(at) }
            mainClock.advanceTimeBy(1_000)
            assertEquals(if (onScreen) 0 else 1, under, "${gap} ms after Close, call screen on screen=$onScreen")
            seen += onScreen
        }
        assertTrue(seen.first() && !seen.last(), "the gaps span the exit transition")
    }

    @Test
    fun floatingTileRestsOverAChatListInBothThemesAndExpandsOnTap() {
        for (dark in listOf(false, true)) runDesktopComposeUiTest(390, 844) {
            var expanded = 0
            var listTaps = 0
            val state = shots.first { it.name == "connected" }.state(base(true, Size.Phone)).copy(minimized = true)
            mainClock.autoAdvance = false
            setContent {
                HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                    Surface(Modifier.fillMaxSize()) {
                        Column {
                            repeat(12) { i ->
                                Row(
                                    Modifier.fillMaxWidth().clickable { listTaps++ }.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape))
                                    Column(Modifier.padding(start = 16.dp)) {
                                        Text(chatNames[i % chatNames.size], style = MaterialTheme.typography.titleMedium)
                                        Text(chatPreviews[i % chatPreviews.size], style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                    CallLayer(visible = true, surface = CallSurface.Tile) {
                        FloatingCallTile(state, "Samwise Gamgee", avatar = {}, onExpand = { expanded++ }, onToggleMute = {}, onHangup = {})
                    }
                }
            }
            mainClock.advanceTimeBy(1_000)
            val tile = onNode(hasTestTag(FloatingTileTag)).getBoundsInRoot()
            assertEquals(390f - 12f, tile.right.value, 1f, "rests top-end")
            assertEquals(64f, tile.top.value, 1f, "below the top bar")
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(outDir, "tile-over-chats-${if (dark) "dark" else "light"}.png"))
            onRoot().performTouchInput { click(Offset(40f, 400f)) }
            assertEquals(1, listTaps, "the app stays usable under the tile")
            onNode(hasTestTag(FloatingTileTag)).performTouchInput { click(Offset(20f, 60f)) }
            assertEquals(1, expanded)
        }
    }

    @Test
    fun floatingTileDragsAndSnapsToTheNearestCorner() = runDesktopComposeUiTest(390, 844) {
        val state = shots.first { it.name == "connected" }.state(base(true, Size.Phone)).copy(minimized = true)
        var expanded = 0
        setContent {
            HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                FloatingCallTile(state, "Samwise Gamgee", avatar = {}, onExpand = { expanded++ }, onToggleMute = {}, onHangup = {})
            }
        }
        onNode(hasTestTag(FloatingTileTag)).performTouchInput { swipe(Offset(20f, 60f), Offset(-200f, 500f), 400L) }
        waitForIdle()
        val tile = onNode(hasTestTag(FloatingTileTag)).getBoundsInRoot()
        assertEquals(12f, tile.left.value, 1f, "snapped to the start edge")
        assertEquals(844f - 88f - 168f, tile.top.value, 1f, "and to the bottom corner, above the bottom bar")
        assertEquals(0, expanded, "a drag is not a tap")
    }

    @Test
    fun remoteVideoLetterboxesOnlyWhenItsOrientationDiffersFromTheScreen() {
        fun remoteBounds(width: Int, height: Int, video: Pair<Int, Int>?): androidx.compose.ui.geometry.Rect {
            var rect = androidx.compose.ui.geometry.Rect.Zero
            runDesktopComposeUiTest(width, height) {
                val connected = shots.first { it.name == "connected" }.state(base(true, Size.Phone))
                show(false, connected.copy(videos = connected.videos.copy(remote = FakeVideo("SG", video))))
                rect = onAllNodes(hasTestTag(RemoteVideoTag), useUnmergedTree = true).fetchSemanticsNodes().single().boundsInRoot
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
