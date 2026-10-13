package id.homebase.chat.calling

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import id.homebase.chat.services.calling.CallDirection
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallVideos
import id.homebase.chat.services.calling.FakeVideo
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
class CallDesktopErgonomicsTest {
    private val outDir = File("build/call-shots").apply { mkdirs() }
    private val hidden = SemanticsMatcher.keyIsDefined(SemanticsProperties.InvisibleToUser)

    private class Recorder {
        val calls = mutableListOf<String>()
        fun actions(mini: Boolean = true) = CallScreenActions(
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
            toggleMini = if (mini) ({ calls += "mini" }) else null,
        )
    }

    private fun call(video: Boolean, phase: CallPhase = CallPhase.Connected, muted: Boolean = false, code: String? = null) = CallUiState(
        verificationCode = code,
        callId = "ergo",
        peer = "samwise.gamgee.demo.rocks",
        direction = CallDirection.Outgoing,
        phase = phase,
        muted = muted,
        localVideo = video,
        remoteVideo = video,
        videos = if (video) CallVideos(FakeVideo(0xFF5C6BC0, "FB"), FakeVideo(0xFF26A69A, "SG")) else CallVideos(),
        connectedAtMs = System.currentTimeMillis() - 83_000,
    )

    private fun CallUiState.withDevices() = copy(
        devices = listOf(
            id.homebase.chat.services.calling.MediaDevice("mic-a", "MacBook Pro Microphone", id.homebase.chat.services.calling.MediaDeviceKind.Mic, isDefault = true),
            id.homebase.chat.services.calling.MediaDevice("spk-a", "MacBook Pro Speakers", id.homebase.chat.services.calling.MediaDeviceKind.Speaker, isDefault = true),
            id.homebase.chat.services.calling.MediaDevice("cam-a", "FaceTime HD Camera", id.homebase.chat.services.calling.MediaDeviceKind.Camera, isDefault = true),
        ),
        selectedDevices = mapOf(
            id.homebase.chat.services.calling.MediaDeviceKind.Mic to "mic-a",
            id.homebase.chat.services.calling.MediaDeviceKind.Speaker to "spk-a",
            id.homebase.chat.services.calling.MediaDeviceKind.Camera to "cam-a",
        ),
    )

    private val avatar: @androidx.compose.runtime.Composable (androidx.compose.ui.unit.Dp) -> Unit = { size ->
        Box(Modifier.size(size).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape), contentAlignment = Alignment.Center) {
            Text("SG", color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }

    private fun ComposeUiTest.showScreen(ui: CallUiState, recorder: Recorder, dark: Boolean = false, mac: Boolean = false, fontScale: Float = 1f, overlay: (@androidx.compose.runtime.Composable () -> Unit)? = null) {
        mainClock.autoAdvance = false
        setContent {
            HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalMacShortcuts provides mac, LocalDensity provides androidx.compose.ui.unit.Density(density.density, fontScale)) {
                    Box(Modifier.fillMaxSize()) {
                        CallScreen(ui = ui, peerName = "Samwise Gamgee", avatar = avatar, actions = recorder.actions(), fillsWindow = true)
                        overlay?.invoke()
                    }
                }
            }
        }
        mainClock.advanceTimeBy(900)
    }

    private fun ComposeUiTest.shoot(name: String, dark: Boolean) {
        ImageIO.write(onAllNodes(isRoot()).onFirst().captureToImage().toAwtImage(), "png", File(outDir, "ergo-$name-${if (dark) "dark" else "light"}.png"))
    }

    @Test
    fun commandDTogglesMuteAndCommandEToggleTheCameraThroughTheActions() {
        val recorder = Recorder()
        runDesktopComposeUiTest(960, 640) {
            showScreen(call(video = true), recorder)
            onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.D) } }
            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { pressKey(Key.E) } }
            onRoot().performKeyInput { pressKey(Key.M) }
            onRoot().performKeyInput { withKeyDown(Key.MetaLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.M) } } }
            onRoot().performKeyInput { pressKey(Key.Escape) }
            assertEquals(listOf("mute", "video", "mute", "mini", "end"), recorder.calls)
        }
    }

    @Test
    fun shiftQuestionMarkOpensTheShortcutsSheet() {
        runDesktopComposeUiTest(960, 640) {
            showScreen(call(video = true), Recorder())
            onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Slash) } }
            mainClock.advanceTimeBy(500)
            onNodeWithText("Keyboard shortcuts").assertExists()
            onNodeWithText("Ctrl+Shift+M").assertExists()
        }
    }

    @Test
    fun hoveringTheMuteButtonShowsItsLabelWithTheShortcut() {
        for ((mac, text) in listOf(false to "Mute microphone (Ctrl+D)", true to "Mute microphone (⌘D)")) {
            runDesktopComposeUiTest(960, 640) {
                showScreen(call(video = true), Recorder(), mac = mac)
                assertTrue(onAllNodesWithText(text).fetchSemanticsNodes().isEmpty())
                onNodeWithContentDescription("Mute microphone").performMouseInput { moveTo(center) }
                mainClock.advanceTimeBy(2_000)
                onNodeWithText(text).assertExists()
            }
        }
    }

    @Test
    fun controlsFadeThreeSecondsAfterThePointerRestsAndReturnOnMove() {
        runDesktopComposeUiTest(960, 640) {
            showScreen(call(video = true), Recorder())
            onNodeWithTag(CallDockTag).assert(!hidden)
            mainClock.advanceTimeBy(1_500)
            onNodeWithTag(CallDockTag).assert(!hidden)
            mainClock.advanceTimeBy(1_000)
            onNodeWithTag(CallDockTag).assert(hidden)
            onNodeWithTag(CallTopBarTag).assert(hidden)
            onRoot().performMouseInput { moveTo(Offset(300f, 200f)) }
            mainClock.advanceTimeBy(100)
            onNodeWithTag(CallDockTag).assert(!hidden)
            mainClock.advanceTimeBy(3_500)
            onNodeWithTag(CallDockTag).assert(hidden)
            onRoot().performKeyInput { pressKey(Key.M) }
            mainClock.advanceTimeBy(100)
            onNodeWithTag(CallDockTag).assert(!hidden)
        }
    }

    @Test
    fun controlsStayWhileATooltipIsUpAndNeverFadeOutsideAConnectedVideoCall() {
        runDesktopComposeUiTest(960, 640) {
            showScreen(call(video = true), Recorder())
            onNodeWithContentDescription("Mute microphone").performMouseInput { moveTo(center) }
            mainClock.advanceTimeBy(6_000)
            onNodeWithTag(CallDockTag).assert(!hidden)
        }
        for (ui in listOf(call(video = false), call(video = true, phase = CallPhase.Outgoing), call(video = true, phase = CallPhase.Reconnecting))) {
            runDesktopComposeUiTest(960, 640) {
                showScreen(ui, Recorder())
                mainClock.advanceTimeBy(8_000)
                onNodeWithTag(CallDockTag).assert(!hidden)
            }
        }
    }

    @Test
    fun aPressOnHiddenControlsNeverReachesTheButtonUnderneath() {
        val recorder = Recorder()
        runDesktopComposeUiTest(960, 640) {
            showScreen(call(video = true), recorder)
            mainClock.advanceTimeBy(4_000)
            onNodeWithTag(CallDockTag).assert(hidden)
            onNodeWithContentDescription("End call", useUnmergedTree = true).performMouseInput { click(center) }
            mainClock.advanceTimeBy(100)
            assertEquals(emptyList(), recorder.calls)
            onNodeWithTag(CallDockTag).assert(!hidden)
            onNodeWithContentDescription("End call").performMouseInput { click(center) }
            assertEquals(listOf("hangup"), recorder.calls)
        }
    }

    @Test
    fun controlsStayWhileTheVerificationCodeCardIsOpen() {
        runDesktopComposeUiTest(960, 640) {
            showScreen(call(video = true, code = "123 456"), Recorder())
            onNodeWithContentDescription("Show verification code").performSemanticsAction(SemanticsActions.OnClick)
            mainClock.advanceTimeBy(6_000)
            onNodeWithTag(CallDockTag).assert(!hidden)
        }
    }

    @Test
    fun smallWindowShowsOneRowOfIconButtonsAndNoLabels() {
        val recorder = Recorder()
        runDesktopComposeUiTest(400, 300) {
            showScreen(call(video = true), recorder)
            onNodeWithContentDescription("Mute microphone").performClick()
            onNodeWithContentDescription("Turn camera off").performClick()
            onNodeWithContentDescription("End call").performClick()
            assertEquals(listOf("mute", "video", "hangup"), recorder.calls)
            assertTrue(onAllNodesWithContentDescription("Back to chat").fetchSemanticsNodes().isNotEmpty())
            val dock = onNodeWithTag(CallDockTag).getBoundsInRoot()
            assertTrue(dock.width < 400.dp && dock.height < 80.dp, "dock is a single compact row: $dock")
            assertTrue(onAllNodesWithText("Mic").fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun miniWindowButtonsAndDoubleClickAct() {
        for (video in listOf(true, false)) {
            val recorder = Recorder()
            val (w, h) = if (video) 320 to 180 else 360 to 88
            runDesktopComposeUiTest(w, h) {
                mainClock.autoAdvance = false
                setContent { HomebaseTheme(darkTheme = false, followsSystemTheme = false) { CallMiniContent(call(video), "Samwise Gamgee", avatar, recorder.actions()) } }
                mainClock.advanceTimeBy(500)
                // Semantic clicks: a pointer click would raise hover tooltips, whose popups cover the neighbouring buttons.
                for (control in listOf("Expand call window", "Mute microphone", "End call")) {
                    onNodeWithContentDescription(control).performSemanticsAction(SemanticsActions.OnClick)
                }
                onNodeWithTag(CallMiniTag).performTouchInput { doubleClick(Offset(w / 2f, h / 2f)) }
                mainClock.advanceTimeBy(500)
                assertEquals(listOf("mini", "mute", "hangup", "mini"), recorder.calls.take(4), "video=$video")
            }
        }
    }

    @Test
    fun draggingTheMiniWindowFromBareSurfaceStartsAndContinuesADrag() {
        var started = 0
        var moves = 0
        runDesktopComposeUiTest(360, 88) {
            mainClock.autoAdvance = false
            setContent {
                HomebaseTheme(darkTheme = false, followsSystemTheme = false) {
                    CallMiniContent(call(video = false), "Samwise Gamgee", avatar, Recorder().actions(), onDragStart = { started++ }, onDrag = { moves++ })
                }
            }
            mainClock.advanceTimeBy(500)
            onNodeWithTag(CallMiniTag).performTouchInput { swipe(Offset(120f, 70f), Offset(200f, 80f), 200) }
            mainClock.advanceTimeBy(500)
            assertEquals(1, started)
            assertTrue(moves >= 1)
        }
    }

    @Test
    fun miniWindowIgnoresBareLettersButKeepsChordsAndEsc() {
        val recorder = Recorder()
        runDesktopComposeUiTest(320, 180) {
            mainClock.autoAdvance = false
            setContent { HomebaseTheme(darkTheme = false, followsSystemTheme = false) { CallMiniContent(call(true), "Samwise Gamgee", avatar, recorder.actions()) } }
            mainClock.advanceTimeBy(500)
            onRoot().performKeyInput { pressKey(Key.M); pressKey(Key.V) }
            onRoot().performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.D) } }
            onRoot().performKeyInput { pressKey(Key.Escape) }
            assertEquals(listOf("mute", "end"), recorder.calls)
        }
    }

    @Test
    fun aVideoCallRingingOutShowsYourOwnCameraFullBleedAndNoSelfTile() {
        runDesktopComposeUiTest(960, 640) {
            showScreen(call(video = true, phase = CallPhase.Outgoing), Recorder())
            onNodeWithTag(SelfStageTag).assertExists()
            assertTrue(onAllNodesWithContentDescription("Your camera preview", substring = true).fetchSemanticsNodes().isEmpty())
        }
        runDesktopComposeUiTest(960, 640) {
            showScreen(call(video = true), Recorder())
            assertTrue(onAllNodes(androidx.compose.ui.test.hasTestTag(SelfStageTag)).fetchSemanticsNodes().isEmpty())
        }
    }

    @Test
    fun screenshotsOfTheCallStates() {
        val states = listOf(
            "outgoing-video" to call(video = true, phase = CallPhase.Outgoing).copy(connectedAtMs = null, peerRinging = true),
            "connected-muted-voice" to call(video = false, muted = true),
            "reconnecting-video" to call(video = true, phase = CallPhase.Reconnecting),
            "remote-camera-off" to call(video = true).copy(remoteVideo = false),
            "failed" to call(video = true, phase = CallPhase.Ended(id.homebase.chat.services.calling.CallEndReason.Failed(id.homebase.chat.services.calling.FailReason.NoRoute))),
            "ended" to call(video = false, phase = CallPhase.Ended(id.homebase.chat.services.calling.CallEndReason.Hangup(remote = true))),
        )
        for (dark in listOf(false, true)) {
            for ((name, ui) in states) {
                runDesktopComposeUiTest(960, 640) {
                    showScreen(ui.withDevices(), Recorder(), dark = dark, mac = true)
                    shoot("state-$name-960x640", dark)
                }
            }
            runDesktopComposeUiTest(1280, 800) {
                showScreen(call(video = false).withDevices(), Recorder(), dark = dark, mac = true)
                shoot("state-connected-voice-1280x800", dark)
            }
            runDesktopComposeUiTest(640, 480) {
                showScreen(call(video = false).withDevices(), Recorder(), dark = dark, mac = true)
                shoot("state-connected-voice-640x480", dark)
            }
            runDesktopComposeUiTest(960, 640) {
                showScreen(call(video = true).withDevices(), Recorder(), dark = dark, mac = true)
                onNodeWithContentDescription("Microphone").performMouseInput { moveTo(center) }
                mainClock.advanceTimeBy(2_000)
                shoot("state-tooltip-mic-chevron", dark)
            }
            runDesktopComposeUiTest(400, 300) {
                showScreen(call(video = true), Recorder(), dark = dark, mac = true, fontScale = 2f)
                shoot("state-compact-400x300-font200", dark)
            }
            runDesktopComposeUiTest(400, 300) {
                showScreen(call(video = true, phase = CallPhase.Outgoing).copy(connectedAtMs = null, peerRinging = true), Recorder(), dark = dark, mac = true)
                shoot("state-outgoing-video-400x300", dark)
            }
        }
    }

    @Test
    fun screenshotsOfTheDesktopErgonomicsSurfaces() {
        for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(960, 640) {
                showScreen(call(video = true), Recorder(), dark = dark, mac = true)
                onNodeWithContentDescription("Mute microphone").performMouseInput { moveTo(center) }
                mainClock.advanceTimeBy(2_000)
                shoot("tooltip-mute", dark)
            }
            runDesktopComposeUiTest(960, 640) {
                showScreen(call(video = true), Recorder(), dark = dark)
                mainClock.advanceTimeBy(4_000)
                shoot("video-960x640-idle", dark)
            }
            runDesktopComposeUiTest(400, 300) {
                showScreen(call(video = true), Recorder(), dark = dark)
                shoot("compact-400x300-video", dark)
            }
            runDesktopComposeUiTest(400, 300) {
                showScreen(call(video = false), Recorder(), dark = dark)
                shoot("compact-400x300-voice", dark)
            }
            for ((video, w, h) in listOf(Triple(true, 320, 180), Triple(false, 360, 88))) {
                runDesktopComposeUiTest(w, h) {
                    mainClock.autoAdvance = false
                    setContent { HomebaseTheme(darkTheme = dark, followsSystemTheme = false) { CallMiniContent(call(video), "Samwise Gamgee", avatar, Recorder().actions()) } }
                    mainClock.advanceTimeBy(500)
                    shoot("mini-${w}x$h", dark)
                }
            }
            runDesktopComposeUiTest(900, 90) {
                mainClock.autoAdvance = false
                setContent {
                    HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                        OngoingCallBar(call(video = false, muted = true), "Samwise Gamgee", onClick = {}, onToggleMute = {}, onHangup = {})
                    }
                }
                mainClock.advanceTimeBy(500)
                shoot("ongoing-bar", dark)
            }
        }
    }
}

