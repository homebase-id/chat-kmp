package id.homebase.core.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
}
