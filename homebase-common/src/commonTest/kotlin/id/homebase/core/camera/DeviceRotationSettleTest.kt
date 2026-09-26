package id.homebase.core.camera

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class DeviceRotationSettleTest {

    private class Harness {
        var raw by mutableStateOf<QuarterTurn?>(null)
        var shown: QuarterTurn? = null
    }

    private fun settleTest(block: ComposeUiTest.(Harness) -> Unit) = runComposeUiTest {
        val harness = Harness()
        mainClock.autoAdvance = false
        setContent { harness.shown = settledRotation(harness.raw) }
        mainClock.advanceTimeByFrame()
        block(harness)
    }

    @Test
    fun uprightUntilTheFirstReading() = settleTest { h ->
        assertEquals(QuarterTurn.R0, h.shown)
    }

    @Test
    fun theFirstReadingIsTakenAtOnce() = settleTest { h ->
        h.raw = QuarterTurn.R90
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        assertEquals(QuarterTurn.R90, h.shown)
    }

    @Test
    fun aLaterTurnWaitsForTheSettleDelay() = settleTest { h ->
        h.raw = QuarterTurn.R0
        mainClock.advanceTimeBy(50)
        h.raw = QuarterTurn.R90
        mainClock.advanceTimeBy(DeviceRotation.SETTLE_MS / 2)
        assertEquals(QuarterTurn.R0, h.shown)
        mainClock.advanceTimeBy(DeviceRotation.SETTLE_MS)
        assertEquals(QuarterTurn.R90, h.shown)
    }

    @Test
    fun aTurnUndoneWithinTheDelayNeverShows() = settleTest { h ->
        h.raw = QuarterTurn.R0
        mainClock.advanceTimeBy(50)
        h.raw = QuarterTurn.R90
        mainClock.advanceTimeBy(DeviceRotation.SETTLE_MS / 2)
        h.raw = QuarterTurn.R0
        repeat(10) {
            mainClock.advanceTimeBy(DeviceRotation.SETTLE_MS / 4)
            assertEquals(QuarterTurn.R0, h.shown)
        }
    }
}
