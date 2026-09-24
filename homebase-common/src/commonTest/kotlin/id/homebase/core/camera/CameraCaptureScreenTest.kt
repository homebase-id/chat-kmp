package id.homebase.core.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.runComposeUiTest
import id.homebase.core.haptics.HapticEvent
import id.homebase.core.haptics.Haptics
import id.homebase.core.ui.theme.HomebaseTheme
import kotlinx.coroutines.flow.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CameraCaptureScreenTest {

    private object NoHaptics : Haptics {
        override fun perform(event: HapticEvent) = Unit
    }

    private fun ComposeUiTest.showCamera(
        engine: FakeCameraEngine,
        modes: CameraModes = CameraModes.PhotoAndVideo,
        initialMode: CaptureMode = CaptureMode.Photo,
        mic: MicPermission = MicPermission(granted = true),
        onRequestMic: () -> Unit = {},
    ) {
        setContent {
            Themed {
                CameraCaptureContent(
                    engine = engine,
                    allowedModes = modes,
                    initialMode = initialMode,
                    mirrorFront = true,
                    mic = mic,
                    onRequestMic = onRequestMic,
                    haptics = NoHaptics,
                    deviceRotation = QuarterTurn.R0,
                    onResult = {},
                    onDismiss = {},
                    preview = { Box(it) },
                )
            }
        }
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) =
        HomebaseTheme(darkTheme = true, followsSystemTheme = false, updatesSystemChrome = false, content = content)

    @Test
    fun timerShowsOnlyWhileRecording() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        onNodeWithTag(TIMER_TAG).assertDoesNotExist()

        engine.uiState.update { it.copy(isRecording = true, recordingStartedAtMs = 0L) }
        waitForIdle()
        onNodeWithTag(TIMER_TAG).assertExists()
    }

    @Test
    fun flashIsHiddenOnALensWithoutAFlashUnit() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        onNodeWithTag(FLASH_TAG).assertExists()

        onNodeWithTag(FLIP_TAG).performClick()
        waitForIdle()
        assertEquals(CameraLens.Front, engine.uiState.value.lens)
        onNodeWithTag(FLASH_TAG).assertDoesNotExist()
    }

    @Test
    fun flashCyclesOffAutoOn() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        repeat(3) {
            onNodeWithTag(FLASH_TAG).performClick()
            waitForIdle()
        }
        assertEquals(listOf("flash:Auto", "flash:On", "flash:Off"), engine.calls.filter { it.startsWith("flash") })
    }

    @Test
    fun modeToggleAndFlipAreDisabledWhileRecording() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        onNodeWithTag(MODE_VIDEO_TAG).assertIsEnabled()

        engine.uiState.update { it.copy(isRecording = true, recordingStartedAtMs = 0L) }
        waitForIdle()
        onNodeWithTag(MODE_VIDEO_TAG).assertIsNotEnabled()
        onNodeWithTag(FLIP_TAG).assertIsNotEnabled()
    }

    @Test
    fun photoOnlyHidesTheModeToggle() = runComposeUiTest {
        showCamera(FakeCameraEngine(), modes = CameraModes.Photo)
        onNodeWithTag(MODE_PHOTO_TAG).assertDoesNotExist()
        onNodeWithTag(MODE_VIDEO_TAG).assertDoesNotExist()
    }

    @Test
    fun photoOnlyNeverStartsInVideo() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine, modes = CameraModes.Photo, initialMode = CaptureMode.Video)
        waitForIdle()
        assertEquals(CaptureMode.Photo, engine.uiState.value.mode)
    }

    @Test
    fun shutterTakesAPhotoInPhotoMode() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        onNodeWithTag(SHUTTER_TAG).performClick()
        waitForIdle()
        assertTrue("photo" in engine.calls)
    }

    @Test
    fun shutterStartsAndStopsARecordingInVideoMode() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine, initialMode = CaptureMode.Video)
        onNodeWithTag(SHUTTER_TAG).performClick()
        waitForIdle()
        assertTrue("record:audio=true" in engine.calls)

        onNodeWithTag(SHUTTER_TAG).performClick()
        waitForIdle()
        assertTrue("stop" in engine.calls)
    }

    @Test
    fun deniedMicRecordsSilentlyAndShowsTheChip() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine, initialMode = CaptureMode.Video, mic = MicPermission(granted = false, askedThisSession = true))
        onNodeWithTag(NO_MIC_TAG).assertExists()
        onNodeWithTag(SHUTTER_TAG).performClick()
        waitForIdle()
        assertTrue("record:audio=false" in engine.calls)
    }

    @Test
    fun enteringVideoAsksForTheMicOnce() = runComposeUiTest {
        var asked = 0
        val engine = FakeCameraEngine()
        showCamera(engine, mic = MicPermission(granted = false), onRequestMic = { asked++ })
        assertEquals(0, asked)
        onNodeWithTag(MODE_VIDEO_TAG).performClick()
        waitForIdle()
        assertEquals(1, asked)
    }

    @Test
    fun aRecordingThatEndsOnItsOwnIsCollected() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        engine.uiState.update { it.copy(isRecording = true, recordingStartedAtMs = 0L) }
        waitForIdle()
        engine.uiState.update { it.copy(isRecording = false, recordingStartedAtMs = null) }
        waitForIdle()
        assertTrue("stop" in engine.calls)
    }

    @Test
    fun zoomPresetsJumpToTheirRatio() = runComposeUiTest {
        val engine = FakeCameraEngine(CameraUiState(isBound = true, minZoom = 1f, maxZoom = 8f))
        showCamera(engine)
        onNodeWithTag(ZOOM_PRESET_TAG + "2").performClick()
        waitForIdle()
        assertEquals(2f, engine.uiState.value.zoomRatio)
    }

    @Test
    fun deviceRotationReachesTheEngine() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        waitForIdle()
        assertTrue("rotation:R0" in engine.calls)
    }

    @Test
    fun unavailableCameraShowsTheEmptyState() = runComposeUiTest {
        showCamera(FakeCameraEngine(CameraUiState(isAvailable = false)))
        onNodeWithTag(UNAVAILABLE_TAG).assertExists()
        onNodeWithTag(SHUTTER_TAG).assertDoesNotExist()
    }

    @Test
    fun checkingPaneOnlyOffersClose() = runComposeUiTest {
        setContent {
            Themed { CameraPermissionPane(state = CameraPermissionState.Checking, onAction = {}, onDismiss = {}) }
        }
        onNodeWithTag(PERMISSION_ACTION_TAG).assertDoesNotExist()
        onNodeWithTag(CLOSE_TAG).assertExists()
    }

    @Test
    fun deniedPaneRetries() = runComposeUiTest {
        var actions = 0
        setContent {
            Themed {
                CameraPermissionPane(state = CameraPermissionState.Denied, onAction = { actions++ }, onDismiss = {})
            }
        }
        onNodeWithTag(PERMISSION_ACTION_TAG).performClick()
        assertEquals(1, actions)
    }

    @Test
    fun permanentlyDeniedPaneOffersSettings() = runComposeUiTest {
        var actions = 0
        setContent {
            Themed {
                CameraPermissionPane(state = CameraPermissionState.PermanentlyDenied, onAction = { actions++ }, onDismiss = {})
            }
        }
        onNodeWithTag(PERMISSION_ACTION_TAG).performClick()
        assertEquals(1, actions)
    }

    @Test
    fun requestingPaneWaitsForTheSystemPrompt() = runComposeUiTest {
        setContent {
            Themed { CameraPermissionPane(state = CameraPermissionState.Requesting, onAction = {}, onDismiss = {}) }
        }
        onNodeWithTag(PERMISSION_PANE_TAG).assertExists()
        onNodeWithTag(PERMISSION_ACTION_TAG).assertDoesNotExist()
    }

    @Test
    fun swipingThePreviewSidewaysChangesMode() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        onNodeWithTag(PREVIEW_TAG).performTouchInput { swipeLeft() }
        waitForIdle()
        assertEquals(CaptureMode.Video, engine.uiState.value.mode)

        onNodeWithTag(PREVIEW_TAG).performTouchInput { swipeRight() }
        waitForIdle()
        assertEquals(CaptureMode.Photo, engine.uiState.value.mode)
    }

    @Test
    fun swipingTheModeCarouselChangesMode() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        onNodeWithTag(MODE_PHOTO_TAG).performTouchInput { swipeLeft(startX = right, endX = left - 200f) }
        waitForIdle()
        assertEquals(CaptureMode.Video, engine.uiState.value.mode)
    }

    @Test
    fun swipeIsIgnoredWhileRecordingAndInPhotoOnly() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine, modes = CameraModes.Photo)
        onNodeWithTag(PREVIEW_TAG).performTouchInput { swipeLeft() }
        waitForIdle()
        assertEquals(CaptureMode.Photo, engine.uiState.value.mode)

        engine.uiState.update { it.copy(isRecording = true, recordingStartedAtMs = 0L) }
        onNodeWithTag(PREVIEW_TAG).performTouchInput { swipeLeft() }
        waitForIdle()
        assertTrue(engine.calls.none { it == "mode:Video" })
    }

    @Test
    fun tapsVerticalDragsAndPinchesDoNotChangeMode() = runComposeUiTest {
        val engine = FakeCameraEngine(CameraUiState(isBound = true, hasFrontLens = true, minZoom = 1f, maxZoom = 8f))
        showCamera(engine)
        onNodeWithTag(PREVIEW_TAG).performTouchInput { click(center) }
        onNodeWithTag(PREVIEW_TAG).performTouchInput { swipeUp() }
        onNodeWithTag(PREVIEW_TAG).performTouchInput {
            pinch(
                start0 = center - Offset(40f, 0f), end0 = center - Offset(200f, 0f),
                start1 = center + Offset(40f, 0f), end1 = center + Offset(200f, 0f),
            )
        }
        waitForIdle()
        assertEquals(CaptureMode.Photo, engine.uiState.value.mode)
        assertTrue(engine.uiState.value.zoomRatio > 1f, "pinch should zoom in")
    }

    @Test
    fun doubleTapOnThePreviewFlipsTheLens() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        onNodeWithTag(PREVIEW_TAG).performTouchInput { doubleClick(center) }
        waitForIdle()
        assertEquals(CameraLens.Front, engine.uiState.value.lens)
    }

    @Test
    fun holdingTheShutterInPhotoRecordsUntilRelease() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        onNodeWithTag(SHUTTER_TAG).performTouchInput {
            down(center)
            advanceEventTime(1_000)
            move()
        }
        waitForIdle()
        assertTrue(engine.uiState.value.isRecording)
        onNodeWithTag(LOCK_TAG).assertExists()

        onNodeWithTag(SHUTTER_TAG).performTouchInput { up() }
        waitForIdle()
        assertTrue("stop" in engine.calls)
        assertEquals(CaptureMode.Photo, engine.uiState.value.mode, "a hold from photo returns to photo")
    }

    @Test
    fun holdWithoutSimultaneousVideoRebindsToVideoFirst() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        onNodeWithTag(SHUTTER_TAG).performTouchInput { longClick(center, durationMillis = 1_000) }
        waitForIdle()
        val modeSwitch = engine.calls.indexOf("mode:Video")
        val record = engine.calls.indexOfFirst { it.startsWith("record") }
        assertTrue(modeSwitch in 0 until record, "switch to video before recording: ${engine.calls}")
    }

    @Test
    fun slidingTowardTheLockKeepsRecordingAfterRelease() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        onNodeWithTag(SHUTTER_TAG).performTouchInput {
            down(center)
            advanceEventTime(1_000)
            move()
        }
        waitForIdle()
        val lockCenter = onNodeWithTag(LOCK_TAG).fetchSemanticsNode().boundsInRoot.center
        val shutterCenter = onNodeWithTag(SHUTTER_TAG).fetchSemanticsNode().boundsInRoot.center
        onNodeWithTag(SHUTTER_TAG).performTouchInput {
            val target = center + Offset(lockCenter.x - shutterCenter.x, 0f)
            repeat(10) { step -> moveTo(center + (target - center) * ((step + 1) / 10f)) }
            up()
        }
        waitForIdle()
        assertTrue(engine.uiState.value.isRecording, "locked recording keeps running: ${engine.calls}")
        assertTrue("stop" !in engine.calls)

        onNodeWithTag(SHUTTER_TAG).performClick()
        waitForIdle()
        assertTrue("stop" in engine.calls)
    }

    @Test
    fun slidingUpWhileHoldingZooms() = runComposeUiTest {
        val engine = FakeCameraEngine(
            CameraUiState(isBound = true, hasFrontLens = true, minZoom = 1f, maxZoom = 8f, supportsSimultaneousVideo = true)
        )
        showCamera(engine)
        onNodeWithTag(SHUTTER_TAG).performTouchInput {
            down(center)
            advanceEventTime(1_000)
            move()
            repeat(10) { moveBy(Offset(0f, -40f)) }
            up()
        }
        waitForIdle()
        assertTrue(engine.calls.any { it.startsWith("zoom:") && it != "zoom:1.0" }, "${engine.calls}")
        assertTrue(engine.calls.none { it == "mode:Video" }, "simultaneous binding records straight from photo")
    }
}
