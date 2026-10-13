package id.homebase.chat.calling

import androidx.compose.foundation.background
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
import id.homebase.chat.services.calling.AudioRoute
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
            for (p in preview) {
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
