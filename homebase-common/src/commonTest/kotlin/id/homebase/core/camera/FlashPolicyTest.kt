package id.homebase.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FlashPolicyTest {
    @Test
    fun photoFlashCyclesOffAutoOn() {
        assertEquals(FlashMode.Auto, FlashPolicy.next(FlashMode.Off))
        assertEquals(FlashMode.On, FlashPolicy.next(FlashMode.Auto))
        assertEquals(FlashMode.Off, FlashPolicy.next(FlashMode.On))
    }

    @Test
    fun ledLensOffersFlashInPhotoAndTorchInVideo() {
        assertEquals(FlashControl.Flash, FlashPolicy.control(CaptureMode.Photo, hasPhotoFlash = true, hasTorch = true))
        assertEquals(FlashControl.Torch, FlashPolicy.control(CaptureMode.Video, hasPhotoFlash = true, hasTorch = true))
    }

    @Test
    fun lensWithoutLightHidesTheControl() {
        assertEquals(FlashControl.Hidden, FlashPolicy.control(CaptureMode.Photo, hasPhotoFlash = false, hasTorch = false))
        assertEquals(FlashControl.Hidden, FlashPolicy.control(CaptureMode.Video, hasPhotoFlash = false, hasTorch = false))
    }

    @Test
    fun iosFrontScreenFlashShowsInPhotoButNoTorchInVideo() {
        assertEquals(FlashControl.Flash, FlashPolicy.control(CaptureMode.Photo, hasPhotoFlash = true, hasTorch = false))
        assertEquals(FlashControl.Hidden, FlashPolicy.control(CaptureMode.Video, hasPhotoFlash = true, hasTorch = false))
    }

    @Test
    fun torchOnlyReachesHardwareInVideoModeWithATorch() {
        assertTrue(FlashPolicy.effectiveTorch(true, CaptureMode.Video, hasTorch = true))
        assertFalse(FlashPolicy.effectiveTorch(true, CaptureMode.Photo, hasTorch = true))
        assertFalse(FlashPolicy.effectiveTorch(true, CaptureMode.Video, hasTorch = false))
        assertFalse(FlashPolicy.effectiveTorch(false, CaptureMode.Video, hasTorch = true))
    }

    @Test
    fun requestedModeSurvivesALensFlipWithoutPhotoFlash() {
        val requested = FlashMode.On
        assertEquals(FlashMode.Off, FlashPolicy.effectivePhotoFlash(requested, hasPhotoFlash = false))
        assertEquals(FlashMode.On, FlashPolicy.effectivePhotoFlash(requested, hasPhotoFlash = true))
    }
}
