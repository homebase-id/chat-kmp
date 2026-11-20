package id.homebase.api.video

import kotlin.test.Test
import kotlin.test.assertEquals

class HardwareVideoBitrateTest {
    private val standard = VideoQuality.STANDARD.targets()

    @Test
    fun aSourceAtOrAboveTheTargetGetsTheTargetBitrate() {
        assertEquals(2_500_000, standard.hardwareVideoBitrate(1920, 1080, null))
        assertEquals(2_500_000, standard.hardwareVideoBitrate(1280, 720, 20_000_000))
    }

    @Test
    fun aSmallerSourceGetsItInProportionToItsArea() {
        assertEquals(625_000, standard.hardwareVideoBitrate(640, 360, null))
    }

    @Test
    fun noSourceGetsMoreThanItsOwn() {
        assertEquals(1_000_000, standard.hardwareVideoBitrate(1920, 1080, 1_000_000))
    }
}
