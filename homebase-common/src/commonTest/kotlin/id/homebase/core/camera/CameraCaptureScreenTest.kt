package id.homebase.core.camera

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.input.key.Key
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import io.github.vinceglb.filekit.PlatformFile
import kotlin.test.assertFalse
import id.homebase.core.haptics.HapticEvent
import id.homebase.core.haptics.Haptics
import id.homebase.core.ui.theme.HomebaseTheme
import kotlinx.coroutines.flow.update
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class CameraCaptureScreenTest {

    private class RecordingHaptics : Haptics {
        val events = mutableListOf<HapticEvent>()
        override fun perform(event: HapticEvent) {
            events += event
        }
    }

    private fun ComposeUiTest.showCamera(
        engine: FakeCameraEngine,
        modes: CameraModes = CameraModes.PhotoAndVideo,
        initialMode: CaptureMode = CaptureMode.Photo,
        mic: MicPermission = MicPermission(granted = true),
        onRequestMic: () -> Unit = {},
        haptics: Haptics = RecordingHaptics(),
        reduceMotion: Boolean = false,
        size: DpSize = PhoneSize,
        deviceRotation: QuarterTurn = QuarterTurn.R0,
        onResult: (PlatformFile) -> Unit = {},
        onOpenGallery: (() -> Unit)? = null,
        preview: @Composable (Modifier) -> Unit = { Box(it) },
    ) {
        setContent {
            Themed {
                CompositionLocalProvider(LocalReduceMotion provides reduceMotion) {
                    Box(Modifier.size(size)) {
                        CameraCaptureContent(
                            engine = engine,
                            allowedModes = modes,
                            initialMode = initialMode,
                            mirrorFront = true,
                            mic = mic,
                            onRequestMic = onRequestMic,
                            haptics = haptics,
                            deviceRotation = deviceRotation,
                            onResult = onResult,
                            onDismiss = {},
                            onOpenGallery = onOpenGallery,
                            preview = preview,
                        )
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.holdShutter() {
        onNodeWithTag(SHUTTER_TAG).performTouchInput {
            down(center)
            advanceEventTime(1_000)
            move()
        }
        waitForIdle()
    }

    /** Moves the held finger [fraction] of the way from the shutter to the lock target, without lifting. */
    private fun ComposeUiTest.slideTowardLock(fraction: Float) {
        val lock = onNodeWithTag(LOCK_TAG).fetchSemanticsNode().boundsInRoot.center
        val shutter = onNodeWithTag(SHUTTER_TAG).fetchSemanticsNode().boundsInRoot.center
        onNodeWithTag(SHUTTER_TAG).performTouchInput {
            val target = center + (lock - shutter) * fraction
            repeat(10) { step -> moveTo(center + (target - center) * ((step + 1) / 10f)) }
        }
        waitForIdle()
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
    fun screenFlashLensOffersFlashInPhotoButNoTorchInVideo() = runComposeUiTest {
        val engine = FakeCameraEngine(CameraUiState(isBound = true, hasPhotoFlash = true, hasTorch = false))
        showCamera(engine)
        onNodeWithTag(FLASH_TAG).assertExists()

        engine.setMode(CaptureMode.Video)
        waitForIdle()
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
    fun aTappedPresetIsSelectedWhileTheZoomRampsToIt() = runComposeUiTest {
        val engine = FakeCameraEngine(CameraUiState(isBound = true, minZoom = 1f, maxZoom = 8f))
        engine.holdAnimatedZoom = true
        showCamera(engine)
        onNodeWithTag(ZOOM_PRESET_TAG + "2").performClick()
        engine.uiState.update { it.copy(zoomRatio = 1.4f) }
        waitForIdle()
        onNodeWithTag(ZOOM_PRESET_TAG + "2").assertIsOn()
        onNodeWithTag(ZOOM_PRESET_TAG + "1").assertIsOff()
        onNodeWithText("1.4×").assertDoesNotExist()

        engine.uiState.update { it.copy(zoomRatio = 2f) }
        waitForIdle()
        // Once it lands, a pinch shows its live ratio on the chip under it again.
        engine.uiState.update { it.copy(zoomRatio = 1.4f) }
        waitForIdle()
        onNodeWithTag(ZOOM_PRESET_TAG + "1").assertIsOn()
        onNodeWithText("1.4×").assertExists()
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
        onNodeWithTag(MODE_PHOTO_TAG).performTouchInput { swipeRight(startX = left, endX = right + 200f) }
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

    private class PreviewTaps {
        var taps = 0
        var longPresses = 0
        val preview: @Composable (Modifier) -> Unit = { modifier ->
            Box(modifier.pointerInput(Unit) { detectPreviewTaps(onTap = { taps++ }, onLongPress = { longPresses++ }) })
        }
    }

    private fun ComposeUiTest.slowDragOnPreview(step: Offset) {
        onNodeWithTag(PREVIEW_TAG).performTouchInput {
            down(center)
            repeat(12) { moveBy(step, delayMillis = 50) }
            up()
        }
        waitForIdle()
    }

    @Test
    fun previewTapAndHoldStillFocusAndLock() = runComposeUiTest {
        val taps = PreviewTaps()
        showCamera(FakeCameraEngine(), preview = taps.preview)
        onNodeWithTag(PREVIEW_TAG).performTouchInput { click(center) }
        onNodeWithTag(PREVIEW_TAG).performTouchInput {
            down(center)
            advanceEventTime(600)
            move()
            up()
        }
        waitForIdle()
        assertEquals(1, taps.taps)
        assertEquals(1, taps.longPresses)
    }

    @Test
    fun slowVerticalDragWithoutAFocusPointDoesNotLockFocus() = runComposeUiTest {
        val taps = PreviewTaps()
        val engine = FakeCameraEngine()
        showCamera(engine, preview = taps.preview)
        slowDragOnPreview(Offset(0f, 6f))
        assertEquals(0, taps.longPresses)
        assertEquals(0, taps.taps)
    }

    @Test
    fun slowSidewaysDragInPhotoOnlyOrWhileRecordingDoesNotLockFocus() = runComposeUiTest {
        val taps = PreviewTaps()
        val engine = FakeCameraEngine()
        showCamera(engine, modes = CameraModes.Photo, preview = taps.preview)
        slowDragOnPreview(Offset(-6f, 0f))

        engine.uiState.update { it.copy(isRecording = true, recordingStartedAtMs = 0L) }
        waitForIdle()
        slowDragOnPreview(Offset(6f, 0f))
        assertEquals(0, taps.longPresses)
        assertEquals(0, taps.taps)
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

    @Test
    fun aPartialCarouselDragSettlesBack() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        onNodeWithTag(MODE_PHOTO_TAG).performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(8f, 0f), delayMillis = 60) }
            up()
        }
        waitForIdle()
        assertEquals(CaptureMode.Photo, engine.uiState.value.mode)
        assertTrue(engine.calls.none { it == "mode:Video" }, "${engine.calls}")
    }

    @Test
    fun aShortFastCarouselFlingCommits() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        onNodeWithTag(MODE_PHOTO_TAG).performTouchInput {
            down(center)
            repeat(4) { moveBy(Offset(10f, 0f), delayMillis = 8) }
            up()
        }
        waitForIdle()
        assertEquals(CaptureMode.Video, engine.uiState.value.mode)
    }

    @Test
    fun aSlowCarouselDragPastHalfASlotCommits() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        onNodeWithTag(MODE_PHOTO_TAG).performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(8f, 0f), delayMillis = 80) }
            advanceEventTime(300)
            up()
        }
        waitForIdle()
        assertEquals(CaptureMode.Video, engine.uiState.value.mode)
    }

    @Test
    fun aPartialPreviewSwipeSettlesBack() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        onNodeWithTag(PREVIEW_TAG).performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(-8f, 0f), delayMillis = 60) }
            up()
        }
        waitForIdle()
        assertEquals(CaptureMode.Photo, engine.uiState.value.mode)
    }

    @Test
    fun carouselDragTicksOncePerSlot() = runComposeUiTest {
        val haptics = RecordingHaptics()
        val engine = FakeCameraEngine()
        showCamera(engine, haptics = haptics)
        onNodeWithTag(MODE_PHOTO_TAG).performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(8f, 0f), delayMillis = 80) }
            advanceEventTime(300)
            up()
        }
        waitForIdle()
        assertEquals(1, haptics.events.count { it == HapticEvent.Selection }, "${haptics.events}")
    }

    @Test
    fun theLockEngagesAtTheThresholdBeforeRelease() = runComposeUiTest {
        val haptics = RecordingHaptics()
        val engine = FakeCameraEngine()
        showCamera(engine, haptics = haptics)
        holdShutter()
        haptics.events.clear()
        slideTowardLock(0.95f)
        assertTrue(HapticEvent.Tick in haptics.events, "reaching the lock arms it: ${haptics.events}")
        onNodeWithTag(SHUTTER_TAG).performTouchInput {
            advanceEventTime(LOCK_DWELL_MS + 50)
            moveBy(Offset(1f, 0f))
        }
        waitForIdle()
        assertTrue(HapticEvent.Confirm in haptics.events, "an armed lock takes while the finger is still down: ${haptics.events}")

        onNodeWithTag(SHUTTER_TAG).performTouchInput { up() }
        waitForIdle()
        assertTrue(engine.uiState.value.isRecording)
        assertTrue("stop" !in engine.calls)
    }

    @Test
    fun releasingShortOfTheLockStops() = runComposeUiTest {
        val haptics = RecordingHaptics()
        val engine = FakeCameraEngine()
        showCamera(engine, haptics = haptics)
        holdShutter()
        haptics.events.clear()
        slideTowardLock(0.5f)
        assertTrue(HapticEvent.Confirm !in haptics.events)

        onNodeWithTag(SHUTTER_TAG).performTouchInput { up() }
        waitForIdle()
        assertTrue("stop" in engine.calls)
    }

    @Test
    fun pullingBackFromAnArmedLockDisarmsIt() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        holdShutter()
        val lock = onNodeWithTag(LOCK_TAG).fetchSemanticsNode().boundsInRoot.center
        val shutter = onNodeWithTag(SHUTTER_TAG).fetchSemanticsNode().boundsInRoot.center
        // Out to the lock and straight back within the dwell, in one gesture so no idle frame can let it take.
        onNodeWithTag(SHUTTER_TAG).performTouchInput {
            moveTo(center + (lock - shutter) * 0.95f, delayMillis = 16)
            moveTo(center, delayMillis = 16)
            up()
        }
        waitForIdle()
        assertTrue("stop" in engine.calls, "released off the lock stops: ${engine.calls}")
    }

    @Test
    fun holdStartSwitchesTheControlsBeforeTheEngineReportsIt() = runComposeUiTest {
        val engine = FakeCameraEngine().apply { reportsRecordingStart = false }
        showCamera(engine)
        onNodeWithTag(TIMER_TAG).assertDoesNotExist()
        holdShutter()
        assertFalse(engine.uiState.value.isRecording)
        onNodeWithTag(TIMER_TAG).assertExists()
        onNodeWithTag(LOCK_TAG).assertExists()
        onNodeWithTag(MODE_VIDEO_TAG).assertIsNotEnabled()
    }

    @Test
    fun aDeliveredHoldFromPhotoDoesNotRebindToPhoto() = runComposeUiTest {
        val engine = FakeCameraEngine().apply { recordingResult = PlatformFile("clip.mp4") }
        var delivered: PlatformFile? = null
        showCamera(engine, onResult = { delivered = it })
        holdShutter()
        onNodeWithTag(SHUTTER_TAG).performTouchInput { up() }
        waitForIdle()
        assertEquals("clip.mp4", delivered?.toString()?.substringAfterLast('/'))
        val stop = engine.calls.indexOf("stop")
        assertTrue(engine.calls.drop(stop).none { it == "mode:Photo" }, "${engine.calls}")
    }

    @Test
    fun theLockHintShowsDuringAHold() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        onNodeWithTag(LOCK_HINT_TAG).assertDoesNotExist()
        holdShutter()
        onNodeWithTag(LOCK_HINT_TAG).assertExists()
    }

    @Test
    fun hapticsDistinguishCaptureStartAndStop() = runComposeUiTest {
        val haptics = RecordingHaptics()
        val engine = FakeCameraEngine()
        showCamera(engine, haptics = haptics)
        onNodeWithTag(SHUTTER_TAG).performClick()
        waitForIdle()
        assertEquals(listOf(HapticEvent.Confirm), haptics.events)

        haptics.events.clear()
        onNodeWithTag(MODE_VIDEO_TAG).performClick()
        waitForIdle()
        haptics.events.clear()
        onNodeWithTag(SHUTTER_TAG).performClick()
        waitForIdle()
        onNodeWithTag(SHUTTER_TAG).performClick()
        waitForIdle()
        assertEquals(listOf(HapticEvent.LongPress, HapticEvent.Confirm), haptics.events)
        assertEquals(1, engine.calls.count { it == "stop" }, "one stop per recording: ${engine.calls}")
    }

    @Test
    fun aPhotoIsDelivered() = runComposeUiTest {
        val engine = FakeCameraEngine().apply { photoResult = PlatformFile("photo.jpg") }
        var delivered: PlatformFile? = null
        showCamera(engine, onResult = { delivered = it })
        onNodeWithTag(SHUTTER_TAG).performClick()
        waitForIdle()
        assertEquals("photo.jpg", delivered?.toString()?.substringAfterLast('/'))
    }

    @Test
    fun recordingStartAndStopAreAnnounced() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine, initialMode = CaptureMode.Video)
        onNodeWithTag(SHUTTER_TAG).performClick()
        waitForIdle()
        onNodeWithTag(ANNOUNCER_TAG).assertContentDescriptionContains("Recording started")

        onNodeWithTag(SHUTTER_TAG).performClick()
        waitForIdle()
        onNodeWithTag(ANNOUNCER_TAG).assertContentDescriptionContains("Recording stopped", substring = true)
    }

    @Test
    fun reduceMotionStillSwitchesModesAndRecords() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine, reduceMotion = true)
        onNodeWithTag(PREVIEW_TAG).performTouchInput { swipeLeft() }
        waitForIdle()
        assertEquals(CaptureMode.Video, engine.uiState.value.mode)

        onNodeWithTag(SHUTTER_TAG).performClick()
        waitForIdle()
        onNodeWithTag(TIMER_TAG).assertExists()
        onNodeWithTag(SHUTTER_TAG).performClick()
        waitForIdle()
        assertTrue("stop" in engine.calls)
    }

    @Test
    fun reduceMotionShowsTheFocusRingAndFlips() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine, reduceMotion = true)
        engine.uiState.update { it.copy(focusPoint = Offset(100f, 200f)) }
        waitForIdle()
        onNodeWithTag(FOCUS_RING_TAG).assertExists()
        onNodeWithTag(FLIP_TAG).performClick()
        waitForIdle()
        assertEquals(CameraLens.Front, engine.uiState.value.lens)
    }

    @Test
    fun volumeKeyPressTakesAPhoto() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.VolumeDown) }
        waitForIdle()
        assertTrue("photo" in engine.calls, "${engine.calls}")
    }

    @Test
    fun holdingAVolumeKeyRecordsUntilRelease() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        waitForIdle()
        onRoot().performKeyInput { keyDown(Key.VolumeUp) }
        mainClock.advanceTimeBy(1_000)
        waitForIdle()
        assertTrue(engine.uiState.value.isRecording, "${engine.calls}")
        onNodeWithTag(LOCK_TAG).assertDoesNotExist()

        onRoot().performKeyInput { keyUp(Key.VolumeUp) }
        waitForIdle()
        assertTrue("stop" in engine.calls)
        assertTrue("photo" !in engine.calls)
    }

    @Test
    fun draggingTheZoomBarZoomsContinuously() = runComposeUiTest {
        val engine = FakeCameraEngine(CameraUiState(isBound = true, minZoom = 1f, maxZoom = 8f))
        showCamera(engine)
        onNodeWithTag(ZOOM_BAR_TAG).performTouchInput {
            down(centerLeft + Offset(4f, 0f))
            repeat(6) { moveBy(Offset(10f, 0f), delayMillis = 30) }
        }
        waitForIdle()
        val ratio = engine.uiState.value.zoomRatio
        assertTrue(ratio > 1f && ZoomPresets.selected(ratio, ZoomPresets.available(1f, 8f, emptyList())) == null, "ratio=$ratio")
        onNodeWithTag(ZOOM_READOUT_TAG).assertExists()
        onNodeWithTag(ZOOM_BAR_TAG).performTouchInput { up() }
        waitForIdle()
    }

    @Test
    fun pinchingPastAPresetTicks() = runComposeUiTest {
        val haptics = RecordingHaptics()
        val engine = FakeCameraEngine(CameraUiState(isBound = true, minZoom = 1f, maxZoom = 8f))
        showCamera(engine, haptics = haptics)
        onNodeWithTag(PREVIEW_TAG).performTouchInput {
            pinch(
                start0 = center - Offset(20f, 0f), end0 = center - Offset(150f, 0f),
                start1 = center + Offset(20f, 0f), end1 = center + Offset(150f, 0f),
            )
        }
        waitForIdle()
        assertTrue(engine.uiState.value.zoomRatio > 2f)
        assertTrue(HapticEvent.Selection in haptics.events, "${haptics.events}")
    }

    @Test
    fun aDoubleTapFlipLeavesNoFocusRing() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        onNodeWithTag(PREVIEW_TAG).performTouchInput { doubleClick(center) }
        engine.uiState.update { it.copy(focusPoint = Offset(200f, 400f)) }
        waitForIdle()
        assertEquals(CameraLens.Front, engine.uiState.value.lens)
        onNodeWithTag(FOCUS_RING_TAG).assertDoesNotExist()
    }

    @Test
    fun verticalDragAfterFocusAdjustsExposure() = runComposeUiTest {
        val engine = FakeCameraEngine(CameraUiState(isBound = true, exposureSupported = true))
        showCamera(engine)
        engine.uiState.update { it.copy(focusPoint = Offset(200f, 400f)) }
        waitForIdle()
        onNodeWithTag(EXPOSURE_TAG).assertExists()
        onNodeWithTag(PREVIEW_TAG).performTouchInput { swipeUp() }
        waitForIdle()
        assertTrue(engine.uiState.value.exposureBias > 0f, "${engine.calls}")
        assertEquals(CaptureMode.Photo, engine.uiState.value.mode)
    }

    @Test
    fun exposureSliderIsAnAdjustableRangeForAccessibility() = runComposeUiTest {
        val engine = FakeCameraEngine(CameraUiState(isBound = true, exposureSupported = true))
        showCamera(engine)
        engine.uiState.update { it.copy(focusPoint = Offset(200f, 400f)) }
        waitForIdle()
        val node = onNodeWithTag(EXPOSURE_TAG)
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0f, -1f..1f)))
        node.performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        waitForIdle()
        assertEquals(0.5f, engine.uiState.value.exposureBias)
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(0.5f, -1f..1f)))
    }

    @Test
    fun verticalDragWithoutFocusLeavesExposureAlone() = runComposeUiTest {
        val engine = FakeCameraEngine(CameraUiState(isBound = true, exposureSupported = true))
        showCamera(engine)
        onNodeWithTag(PREVIEW_TAG).performTouchInput { swipeDown() }
        waitForIdle()
        assertTrue(engine.calls.none { it.startsWith("exposure") })
    }

    @Test
    fun aeAfLockShowsItsPill() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        onNodeWithTag(AE_LOCK_TAG).assertDoesNotExist()
        engine.uiState.update { it.copy(focusPoint = Offset(200f, 400f), focusLocked = true) }
        waitForIdle()
        onNodeWithTag(AE_LOCK_TAG).assertExists()
    }

    @Test
    fun recordingHidesTheCarouselWithoutMovingTheShutter() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine, initialMode = CaptureMode.Video)
        waitForIdle()
        val before = onNodeWithTag(SHUTTER_TAG).fetchSemanticsNode().boundsInRoot
        onNodeWithTag(SHUTTER_TAG).performClick()
        waitForIdle()
        assertEquals(before, onNodeWithTag(SHUTTER_TAG).fetchSemanticsNode().boundsInRoot)
    }

    @Test
    fun aWideWindowPutsTheShutterOnASideRail() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine, size = DpSize(800.dp, 400.dp))
        holdShutter()
        val shutter = onNodeWithTag(SHUTTER_TAG).fetchSemanticsNode().boundsInRoot
        val lock = onNodeWithTag(LOCK_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(shutter.center.x > 600f, "shutter on the end edge: $shutter")
        assertTrue(lock.center.y > shutter.center.y, "lock below the shutter: $lock vs $shutter")

        slideTowardLock(0.95f)
        onNodeWithTag(SHUTTER_TAG).performTouchInput { up() }
        waitForIdle()
        assertTrue("stop" !in engine.calls, "rail lock engages too: ${engine.calls}")
    }

    @Test
    fun aTabletWidthKeepsTheShutterRowCompact() = runComposeUiTest {
        showCamera(FakeCameraEngine(), size = DpSize(900.dp, 1200.dp))
        val shutter = onNodeWithTag(SHUTTER_TAG).fetchSemanticsNode().boundsInRoot
        val flip = onNodeWithTag(FLIP_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(flip.center.x - shutter.center.x <= 240f, "flip ${flip.center.x} vs shutter ${shutter.center.x}")
    }

    @Test
    fun pressingALockedShutterStops() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        holdShutter()
        slideTowardLock(0.95f)
        onNodeWithTag(SHUTTER_TAG).performTouchInput { up() }
        waitForIdle()
        assertFalse("stop" in engine.calls)
        onNodeWithTag(SHUTTER_TAG).performClick()
        waitForIdle()
        assertTrue("stop" in engine.calls)
    }

    @Test
    fun snackbarTurnsWithTheIconsWhenTheDeviceIsSideways() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine, deviceRotation = QuarterTurn.R90)
        engine.errors.tryEmit(CameraError.BindFailed)
        waitForIdle()
        val bounds = onNodeWithText("Couldn't start the camera").assertExists().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.height > bounds.width, "sideways snackbar should read along the long edge: $bounds")
    }

    @Test
    fun snackbarStaysFlatWhenTheDeviceIsUpright() = runComposeUiTest {
        val engine = FakeCameraEngine()
        showCamera(engine)
        engine.errors.tryEmit(CameraError.BindFailed)
        waitForIdle()
        val bounds = onNodeWithText("Couldn't start the camera").assertExists().fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width > bounds.height, "upright snackbar should lie flat: $bounds")
    }

    @Test
    fun theGalleryButtonShowsOnlyWithAHandlerAndHidesWhileRecording() = runComposeUiTest {
        val engine = FakeCameraEngine()
        var opened = 0
        var handler by mutableStateOf<(() -> Unit)?>(null)
        setContent {
            Themed {
                Box(Modifier.size(PhoneSize)) {
                    CameraCaptureContent(
                        engine = engine,
                        allowedModes = CameraModes.PhotoAndVideo,
                        initialMode = CaptureMode.Photo,
                        mirrorFront = true,
                        mic = MicPermission(granted = true),
                        onRequestMic = {},
                        haptics = RecordingHaptics(),
                        deviceRotation = QuarterTurn.R0,
                        onResult = {},
                        onDismiss = {},
                        onOpenGallery = handler,
                        preview = { Box(it) },
                    )
                }
            }
        }
        onNodeWithTag(GALLERY_TAG).assertDoesNotExist()

        handler = { opened++ }
        waitForIdle()
        onNodeWithTag(GALLERY_TAG).performClick()
        waitForIdle()
        assertEquals(1, opened)

        engine.uiState.update { it.copy(isRecording = true, recordingStartedAtMs = 0L) }
        waitForIdle()
        onNodeWithTag(GALLERY_TAG).assertDoesNotExist()
    }

    @Test
    fun aLetterboxedPreviewSitsUnderTheTopBarAtItsAspect() = runComposeUiTest {
        val engine = FakeCameraEngine(CameraUiState(isBound = true, previewAspectRatio = 3f / 4f))
        showCamera(engine, preview = { Box(it.testTag("frame")) })
        waitForIdle()
        val root = onRoot().fetchSemanticsNode().boundsInRoot
        val frame = onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot
        assertEquals(root.width, frame.width, 1f)
        assertEquals(frame.width * 4f / 3f, frame.height, 1f)
        assertTrue(frame.top > 0f && frame.bottom < root.bottom, "frame $frame in $root")

        engine.uiState.update { it.copy(previewAspectRatio = null) }
        waitForIdle()
        assertEquals(root, onNodeWithTag("frame").fetchSemanticsNode().boundsInRoot)
    }
}

internal val PhoneSize = DpSize(400.dp, 800.dp)
