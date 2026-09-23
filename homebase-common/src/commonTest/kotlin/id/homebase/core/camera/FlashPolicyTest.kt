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
    fun videoModeOffersTorch() {
        assertEquals(FlashControl.Torch, FlashPolicy.control(CaptureMode.Video, hasFlashUnit = true))
        assertEquals(FlashControl.Flash, FlashPolicy.control(CaptureMode.Photo, hasFlashUnit = true))
    }

    @Test
    fun noFlashUnitHidesTheControl() {
        assertEquals(FlashControl.Hidden, FlashPolicy.control(CaptureMode.Photo, hasFlashUnit = false))
        assertEquals(FlashControl.Hidden, FlashPolicy.control(CaptureMode.Video, hasFlashUnit = false))
    }

    @Test
    fun torchOnlyReachesHardwareInVideoModeWithAFlashUnit() {
        assertTrue(FlashPolicy.effectiveTorch(true, CaptureMode.Video, hasFlashUnit = true))
        assertFalse(FlashPolicy.effectiveTorch(true, CaptureMode.Photo, hasFlashUnit = true))
        assertFalse(FlashPolicy.effectiveTorch(true, CaptureMode.Video, hasFlashUnit = false))
        assertFalse(FlashPolicy.effectiveTorch(false, CaptureMode.Video, hasFlashUnit = true))
    }

    @Test
    fun requestedModeSurvivesALensFlipWithoutFlashUnit() {
        val requested = FlashMode.On
        assertEquals(FlashMode.Off, FlashPolicy.effectivePhotoFlash(requested, hasFlashUnit = false))
        assertEquals(FlashMode.On, FlashPolicy.effectivePhotoFlash(requested, hasFlashUnit = true))
    }
}
