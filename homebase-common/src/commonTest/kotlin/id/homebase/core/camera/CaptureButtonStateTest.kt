package id.homebase.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CaptureButtonStateTest {
    @Test
    fun idleStateFollowsMode() {
        assertEquals(CaptureButtonState.Photo, CaptureButtonState.of(CaptureMode.Photo, false, false))
        assertEquals(CaptureButtonState.Video, CaptureButtonState.of(CaptureMode.Video, false, false))
    }

    @Test
    fun recordingWinsOverModeEitherWay() {
        for (mode in CaptureMode.entries) {
            assertEquals(CaptureButtonState.RecordingHeld, CaptureButtonState.of(mode, true, false))
            assertEquals(CaptureButtonState.RecordingLocked, CaptureButtonState.of(mode, true, true))
        }
    }

    @Test
    fun leftoverLockWithoutRecordingIsIgnored() {
        assertEquals(CaptureButtonState.Photo, CaptureButtonState.of(CaptureMode.Photo, false, true))
    }

    @Test
    fun isRecordingOnlyForRecordingStates() {
        assertFalse(CaptureButtonState.Photo.isRecording)
        assertFalse(CaptureButtonState.Video.isRecording)
        assertTrue(CaptureButtonState.RecordingHeld.isRecording)
        assertTrue(CaptureButtonState.RecordingLocked.isRecording)
    }

    @Test
    fun tapActions() {
        assertEquals(CaptureAction.TakePhoto, CaptureButtonState.Photo.tapAction)
        assertEquals(CaptureAction.StartLockedRecording, CaptureButtonState.Video.tapAction)
        assertEquals(CaptureAction.StopRecording, CaptureButtonState.RecordingLocked.tapAction)
        assertNull(CaptureButtonState.RecordingHeld.tapAction)
    }

    @Test
    fun longPressStartsHeldRecordingOnlyWhenAllowed() {
        assertEquals(CaptureAction.StartHeldRecording, CaptureButtonState.Photo.longPressAction(true))
        assertNull(CaptureButtonState.Photo.longPressAction(false))
        assertNull(CaptureButtonState.RecordingLocked.longPressAction(true))
    }

    @Test
    fun holdRecordsWheneverVideoIsAllowed() {
        assertTrue(CameraModes.PhotoAndVideo.recordsVideo)
        assertFalse(CameraModes.Photo.recordsVideo)
    }

    @Test
    fun onlyTheFrontLensMirrorsAndOnlyWhenPreferred() {
        assertTrue(CameraUiState(lens = CameraLens.Front, mirrorFront = true).mirrorsCapture)
        assertFalse(CameraUiState(lens = CameraLens.Front, mirrorFront = false).mirrorsCapture)
        assertFalse(CameraUiState(lens = CameraLens.Back, mirrorFront = true).mirrorsCapture)
        assertFalse(CameraUiState(lens = CameraLens.Back, mirrorFront = false).mirrorsCapture)
    }

    @Test
    fun holdFromPhotoSwitchesToVideoOnlyWithoutSimultaneousBinding() {
        assertTrue(CaptureButtonState.holdSwitchesToVideo(CaptureMode.Photo, supportsSimultaneousVideo = false))
        assertFalse(CaptureButtonState.holdSwitchesToVideo(CaptureMode.Photo, supportsSimultaneousVideo = true))
        assertFalse(CaptureButtonState.holdSwitchesToVideo(CaptureMode.Video, supportsSimultaneousVideo = false))
    }
}
