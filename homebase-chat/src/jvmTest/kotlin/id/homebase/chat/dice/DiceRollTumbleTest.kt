package id.homebase.chat.dice

import androidx.compose.runtime.mutableStateListOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class DiceRollTumbleTest {

    /**
     * Regression for the 1d4 shake crash. A stale `doRoll` closure can hold an
     * out-of-date `count` while `LaunchedEffect(count, faces)` has already
     * resized `displayValues` smaller. Without the `minOf` clamp inside
     * `runTumble`, the next frame writes `displayValues[1]` on a size-1 list
     * and the main thread crashes with `IndexOutOfBoundsException: index 1,
     * size 1`. With the clamp, the loop silently writes only the in-range
     * positions and completes.
     */
    @Test
    fun tumble_survivesMidFlightShrink() = runTest {
        val displayValues = mutableStateListOf<Int?>(null, null)
        val frameDelayMs = 10L
        val totalFrames = 4

        val job = launch {
            runTumble(
                frames = totalFrames,
                count = 2,
                faces = 4,
                displayValues = displayValues,
                frameDelayMs = frameDelayMs,
                randomFace = { 3 },
            )
        }

        advanceTimeBy(frameDelayMs / 2)
        displayValues.removeAt(displayValues.lastIndex)
        assertEquals(1, displayValues.size)

        advanceTimeBy(frameDelayMs * totalFrames + frameDelayMs)
        job.join()

        assertEquals(1, displayValues.size)
        assertEquals(3, displayValues[0])
    }

    @Test
    fun tumble_populatesEveryPositionWhenSizeMatchesCount() = runTest {
        val displayValues = mutableStateListOf<Int?>(null, null, null)

        val job = launch {
            runTumble(
                frames = 2,
                count = 3,
                faces = 6,
                displayValues = displayValues,
                frameDelayMs = 5L,
                randomFace = { 5 },
            )
        }
        advanceTimeBy(50L)
        job.join()

        for (v in displayValues) assertNotNull(v)
        for (v in displayValues) assertEquals(5, v)
    }

    @Test
    fun tumble_doesNothingWhenListIsEmpty() = runTest {
        val displayValues = mutableStateListOf<Int?>()

        val job = launch {
            runTumble(
                frames = 3,
                count = 5,
                faces = 6,
                displayValues = displayValues,
                frameDelayMs = 5L,
                randomFace = { 1 },
            )
        }
        advanceTimeBy(50L)
        job.join()

        assertEquals(0, displayValues.size)
    }
}
