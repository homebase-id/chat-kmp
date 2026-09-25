package id.homebase.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceRotationTest {
    private fun turn(d: Int, current: QuarterTurn? = null) = DeviceRotation.quarterTurnFor(d, current)

    @Test
    fun bucketCentres() {
        assertEquals(QuarterTurn.R0, turn(0))
        assertEquals(QuarterTurn.R90, turn(90))
        assertEquals(QuarterTurn.R180, turn(180))
        assertEquals(QuarterTurn.R270, turn(270))
    }

    @Test
    fun bucketEdges() {
        assertEquals(QuarterTurn.R0, turn(30))
        assertEquals(QuarterTurn.R0, turn(330))
        assertEquals(QuarterTurn.R0, turn(359))
        assertEquals(QuarterTurn.R90, turn(60))
        assertEquals(QuarterTurn.R90, turn(120))
        assertEquals(QuarterTurn.R180, turn(150))
        assertEquals(QuarterTurn.R180, turn(210))
        assertEquals(QuarterTurn.R270, turn(240))
        assertEquals(QuarterTurn.R270, turn(300))
    }

    @Test
    fun deadBandKeepsCurrent() {
        assertEquals(QuarterTurn.R0, turn(45, QuarterTurn.R0))
        assertEquals(QuarterTurn.R90, turn(45, QuarterTurn.R90))
        assertEquals(QuarterTurn.R270, turn(315, QuarterTurn.R270))
        assertEquals(QuarterTurn.R180, turn(135, QuarterTurn.R180))
    }

    @Test
    fun deadBandWithNoCurrentIsUndecided() {
        assertNull(turn(45))
    }

    @Test
    fun sweepPastDiagonalSwitchesOnlyOnceInsideNextBucket() {
        var current: QuarterTurn? = QuarterTurn.R0
        val seen = (0..90 step 5).map { d -> DeviceRotation.quarterTurnFor(d, current).also { current = it } }
        assertEquals(QuarterTurn.R0, seen[(55 / 5)])
        assertEquals(QuarterTurn.R90, seen[(60 / 5)])
    }

    @Test
    fun outOfRangeReadingsWrap() {
        assertEquals(QuarterTurn.R0, turn(360))
        assertEquals(QuarterTurn.R270, turn(-90))
    }

    @Test
    fun iconsCounterRotate() {
        assertEquals(-90f, QuarterTurn.R90.uprightIconDegrees)
        assertEquals(0f, QuarterTurn.R0.uprightIconDegrees)
    }

    @Test
    fun gravityMapsToTheSameTurnsAsUiDeviceOrientation() {
        fun gravityTurn(x: Double, y: Double) = DeviceRotation.degreesForGravity(x, y, 0.0)?.let { turn(it) }
        assertEquals(QuarterTurn.R0, gravityTurn(0.0, -1.0))
        // Top edge to the right: UIDeviceOrientationLandscapeRight.
        assertEquals(QuarterTurn.R90, gravityTurn(1.0, 0.0))
        assertEquals(QuarterTurn.R180, gravityTurn(0.0, 1.0))
        assertEquals(QuarterTurn.R270, gravityTurn(-1.0, 0.0))
    }

    @Test
    fun aTiltedLandscapeHoldStillReadsLandscape() {
        assertEquals(QuarterTurn.R90, DeviceRotation.degreesForGravity(0.7, -0.2, -0.6)?.let { turn(it) })
    }

    @Test
    fun aPhoneLyingFlatHasNoReading() {
        assertNull(DeviceRotation.degreesForGravity(0.1, -0.1, -0.99))
    }
}
