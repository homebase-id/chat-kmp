package id.homebase.api.video

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class FfmpegSessionStopTest {

    @Test
    fun aCancelledWaitEndsOnlyOnceTheSessionHasStopped() = runTest {
        val finished = CompletableDeferred<Unit>()
        var cancels = 0
        val wait = launch { awaitFfmpegSession(finished, 1L) { cancels++ } }
        runCurrent()

        wait.cancel()
        advanceTimeBy(2.seconds)
        assertFalse(wait.isCompleted)
        assertTrue(cancels > 1, "a cancel before the session runs does nothing, so it's repeated")

        finished.complete(Unit)
        runCurrent()
        assertTrue(wait.isCompleted)
    }

    @Test
    fun aSessionThatNeverStopsDoesNotHoldTheWaitForever() = runTest {
        val wait = launch { awaitFfmpegSession(CompletableDeferred<Unit>(), 1L) {} }
        runCurrent()

        wait.cancel()
        advanceTimeBy(31.seconds)
        runCurrent()

        assertTrue(wait.isCompleted)
    }
}
