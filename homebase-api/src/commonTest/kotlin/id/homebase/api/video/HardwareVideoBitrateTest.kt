package id.homebase.api.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HardwareVideoBitrateTest {
    private val standard = VideoQuality.STANDARD.targets()
    private val hd = VideoQuality.HIGH.targets()

    @Test
    fun hdNeverGetsLessThanStandardForTheSameSource() {
        for (shortSide in listOf(240, 360, 480, 540, 720, 810, 900, 1000, 1080, 1440, 2160)) {
            for (sourceBitrate in listOf(null, 1_000_000, 2_500_000, 20_000_000)) {
                val w = shortSide * 16 / 9
                val hdRate = hd.hardwareVideoBitrate(w, shortSide, sourceBitrate)
                val standardRate = standard.hardwareVideoBitrate(w, shortSide, sourceBitrate)
                assertTrue(hdRate >= standardRate, "${shortSide}p at $sourceBitrate: HD $hdRate, Standard $standardRate")
            }
        }
    }

    @Test
    fun hdFloorsAtStandardOnlyWhereStandardAsksMore() {
        assertEquals(2_500_000, hd.hardwareVideoBitrate(1280, 720, null))
        assertEquals(3_472_222, hd.hardwareVideoBitrate(1600, 900, null))
        assertEquals(5_000_000, hd.hardwareVideoBitrate(1920, 1080, null))
    }

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
