package id.homebase.chat.calling

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import id.homebase.chat.services.calling.CallDirection
import id.homebase.chat.services.calling.CallPhase
import id.homebase.chat.services.calling.CallVideos
import id.homebase.chat.services.calling.FakeVideo
import id.homebase.chat.services.calling.MediaDevice
import id.homebase.chat.services.calling.MediaDeviceKind
import id.homebase.core.ui.theme.HomebaseTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CallDevicesScreenshotTest {
    private val outDir = File("build/call-shots").apply { mkdirs() }

    private val devices = listOf(
        MediaDevice("mic-a", "MacBook Pro Microphone", MediaDeviceKind.Mic, isDefault = true),
        MediaDevice("mic-b", "USB Podcast Mic", MediaDeviceKind.Mic, isDefault = false),
        MediaDevice("spk-a", "MacBook Pro Speakers", MediaDeviceKind.Speaker, isDefault = true),
        MediaDevice("spk-b", "AirPods Pro", MediaDeviceKind.Speaker, isDefault = false),
        MediaDevice("cam-a", "FaceTime HD Camera", MediaDeviceKind.Camera, isDefault = false),
        MediaDevice("cam-b", "Studio Display Camera", MediaDeviceKind.Camera, isDefault = false),
    )

    private fun state(micLevel: Float = 0f) = CallUiState(
        callId = "shot",
        peer = "samwise.gamgee.demo.rocks",
        direction = CallDirection.Outgoing,
        phase = CallPhase.Connected,
        localVideo = true,
        remoteVideo = true,
        videos = CallVideos(FakeVideo(0xFF5C6BC0, "FB"), FakeVideo(0xFF26A69A, "SG")),
        connectedAtMs = System.currentTimeMillis() - 83_000,
        verificationCode = "123 456",
        devices = devices,
        selectedDevices = mapOf(MediaDeviceKind.Mic to "mic-b", MediaDeviceKind.Speaker to "spk-a", MediaDeviceKind.Camera to "cam-a"),
        micLevel = micLevel,
    )

    private val picked = mutableListOf<Pair<MediaDeviceKind, String>>()

    // A popup window is not part of the captured root, so the open menu is drawn inline over the stage.
    private fun ComposeUiTest.show(
        dark: Boolean,
        ui: CallUiState,
        snackbar: SnackbarHostState? = null,
        menu: List<MediaDeviceKind>? = null,
        menuAlign: Alignment = Alignment.TopEnd,
        menuPadding: PaddingValues = PaddingValues(top = 64.dp, end = 12.dp),
        notice: DeviceSnackbarVisuals = DeviceSnackbarVisuals("USB Podcast Mic disconnected, using MacBook Pro Microphone", "Change", MediaDeviceKind.Mic),
        deviceMenu: MutableState<MediaDeviceKind?>? = null,
    ) {
        mainClock.autoAdvance = false
        setContent {
            HomebaseTheme(darkTheme = dark, followsSystemTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    CallScreen(
                        ui = ui,
                        peerName = "Samwise Gamgee",
                        avatar = { },
                        actions = CallScreenActions(
                            acceptAudio = {}, acceptVideo = {}, decline = {}, hangup = {}, toggleMute = {}, toggleVideo = {},
                            switchCamera = {}, setRoute = {}, onVerificationClick = {}, retry = {}, close = {}, minimize = {},
                            endFromKeyboard = {}, selectDevice = { kind, id -> picked += kind to id },
                        ),
                        fillsWindow = true,
                        deviceMenu = deviceMenu ?: remember { mutableStateOf(null) },
                    )
                    if (menu != null) {
                        Surface(
                            modifier = Modifier.align(menuAlign).padding(menuPadding).width(IntrinsicSize.Max).widthIn(min = 260.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shadowElevation = 8.dp,
                        ) {
                            Column(Modifier.padding(vertical = 8.dp)) { CallDeviceMenuItems({}, ui, menu) { _, _ -> } }
                        }
                    }
                    if (snackbar != null) {
                        CallDeviceSnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).padding(top = 80.dp))
                        LaunchedEffect(Unit) { snackbar.showSnackbar(notice) }
                    }
                }
            }
        }
        mainClock.advanceTimeBy(900)
    }

    private fun ComposeUiTest.save(name: String, dark: Boolean) {
        mainClock.advanceTimeBy(400)
        val image = onAllNodes(isRoot())[0].captureToImage().toAwtImage()
        ImageIO.write(image, "png", File(outDir, "$name-${if (dark) "dark" else "light"}.png"))
    }

    @Test
    fun topBarMenuListsEveryKindWithTheSelectionAndSystemDefaultMarked() {
        for (dark in listOf(false, true)) runDesktopComposeUiTest(960, 640) {
            show(dark, state(), menu = MediaDeviceKind.entries)
            for (text in listOf("MacBook Pro Microphone", "USB Podcast Mic", "MacBook Pro Speakers", "AirPods Pro", "FaceTime HD Camera", "Studio Display Camera", "System default")) {
                assertTrue(onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "menu shows $text")
            }
            save("devices-menu", dark)
        }
    }

    @Test
    fun micChevronOpensTheAudioMenu() {
        for (dark in listOf(false, true)) runDesktopComposeUiTest(960, 640) {
            show(dark, state(), menu = listOf(MediaDeviceKind.Mic, MediaDeviceKind.Speaker), menuAlign = Alignment.BottomStart, menuPadding = PaddingValues(start = 330.dp, bottom = 110.dp))
            assertTrue(onAllNodes(hasText("USB Podcast Mic"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            assertTrue(onAllNodes(hasText("AirPods Pro"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
            assertEquals(0, onAllNodes(hasText("Studio Display Camera"), useUnmergedTree = true).fetchSemanticsNodes().size)
            save("mic-chevron-menu", dark)
        }
    }

    @Test
    fun pickersRenderInACompactAndALargeWindow() {
        for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(480, 560) {
                show(dark, state(micLevel = 0.4f), menu = MediaDeviceKind.entries, menuPadding = PaddingValues(top = 64.dp, end = 12.dp))
                save("devices-menu-compact", dark)
            }
            runDesktopComposeUiTest(1440, 900) {
                show(dark, state(micLevel = 0.4f), menu = listOf(MediaDeviceKind.Mic, MediaDeviceKind.Speaker), menuAlign = Alignment.BottomCenter, menuPadding = PaddingValues(end = 120.dp, bottom = 120.dp))
                save("mic-chevron-menu-large", dark)
            }
        }
    }

    @Test
    fun chevronsAnnounceTheDeviceInUse() = runDesktopComposeUiTest(960, 640) {
        show(false, state())
        assertTrue(onAllNodes(hasContentDescription("Microphone") and hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "USB Podcast Mic")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodes(hasContentDescription("Camera") and hasClickAction() and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "FaceTime HD Camera")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun micButtonShowsTheLevelRing() {
        for (dark in listOf(false, true)) runDesktopComposeUiTest(960, 640) {
            show(dark, state(micLevel = 0.6f))
            save("mic-level-ring", dark)
        }
    }

    @Test
    fun disconnectSnackbarAppearsAboveTheDock() {
        for (dark in listOf(false, true)) runDesktopComposeUiTest(960, 640) {
            show(dark, state(), SnackbarHostState())
            assertTrue(onAllNodes(hasText("disconnected, using", substring = true)).fetchSemanticsNodes().isNotEmpty())
            assertTrue(onAllNodes(hasText("Change") and hasClickAction()).fetchSemanticsNodes().isNotEmpty())
            save("device-disconnected-snackbar", dark)
        }
    }

    @Test
    fun aReturningDeviceOffersToSwitchBack() {
        for (dark in listOf(false, true)) runDesktopComposeUiTest(960, 640) {
            show(dark, state(), SnackbarHostState(), notice = DeviceSnackbarVisuals("USB Podcast Mic is connected again", "Switch", MediaDeviceKind.Mic))
            assertTrue(onAllNodes(hasText("Switch") and hasClickAction()).fetchSemanticsNodes().isNotEmpty())
            save("device-returned-snackbar", dark)
        }
    }

    @Test
    fun aMenuRequestOpensTheAudioMenuAtTheMicSegment() = runDesktopComposeUiTest(960, 640) {
        val request = mutableStateOf<MediaDeviceKind?>(null)
        show(false, state(), deviceMenu = request)
        assertEquals(0, onAllNodes(hasText("AirPods Pro"), useUnmergedTree = true).fetchSemanticsNodes().size)
        request.value = MediaDeviceKind.Mic
        mainClock.advanceTimeBy(400)
        assertTrue(onAllNodes(hasText("AirPods Pro"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(onAllNodes(hasText("Input level"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun mutedWithTheCameraOffAndAFocusedMenuSegment() {
        for (dark in listOf(false, true)) {
            runDesktopComposeUiTest(960, 640) {
                show(dark, state().copy(muted = true, localVideo = false))
                onNode(hasContentDescription("Unmute microphone")).assertExists()
                onAllNodes(hasContentDescription("Microphone") and hasClickAction())[0].performSemanticsAction(SemanticsActions.RequestFocus)
                save("muted-camera-off-focus", dark)
            }
            runDesktopComposeUiTest(480, 560) {
                show(dark, state(micLevel = 0.7f))
                save("dock-compact", dark)
            }
        }
    }

    @Test
    fun theMicAnnouncesItsDevice() = runDesktopComposeUiTest(960, 640) {
        show(false, state())
        assertTrue(onAllNodes(hasContentDescription("Mute microphone") and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Microphone on, USB Podcast Mic")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun phoneRouteAndFlipTogglesAreGoneWhenPickersExist() = runDesktopComposeUiTest(960, 640) {
        show(false, state().copy(availableRoutes = listOf(id.homebase.chat.services.calling.AudioRoute.Earpiece, id.homebase.chat.services.calling.AudioRoute.Speaker)))
        assertEquals(0, onAllNodes(hasContentDescription("Choose audio output", substring = true) or hasContentDescription("Switch camera", substring = true)).fetchSemanticsNodes().size)
    }

    @Test
    fun devicesButtonAndChevronsOpenRealMenusAndReportTheChoice() = runDesktopComposeUiTest(960, 640) {
        show(false, state())
        onNode(hasContentDescription("Devices") and hasClickAction()).performClick()
        mainClock.advanceTimeBy(400)
        onNode(hasText("AirPods Pro")).performClick()
        assertEquals(listOf(MediaDeviceKind.Speaker to "spk-b"), picked)

        picked.clear()
        mainClock.advanceTimeBy(400)
        onAllNodes(hasContentDescription("Camera") and hasClickAction())[0].performClick()
        mainClock.advanceTimeBy(400)
        assertEquals(0, onAllNodes(hasText("AirPods Pro"), useUnmergedTree = true).fetchSemanticsNodes().size)
        onNode(hasText("Studio Display Camera")).performClick()
        assertEquals(listOf(MediaDeviceKind.Camera to "cam-b"), picked)
    }
}
