package id.homebase.core.camera

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CaptureHandoffTest {
    @Test
    fun cameraWaitsForTheReceiverToDrawTheCapture() = runTest {
        val epoch = CaptureHandoff.begin()
        val waiting = async { awaitHandoff(epoch) }
        advanceTimeBy(HANDOFF_CEILING_MS - 100)
        runCurrent()
        assertFalse(waiting.isCompleted)

        CaptureHandoff.contentShown()
        runCurrent()
        assertTrue(waiting.await())
    }

    @Test
    fun aSignalThatLandsBeforeTheWaitStillCounts() = runTest {
        val epoch = CaptureHandoff.begin()
        CaptureHandoff.contentShown()
        assertTrue(awaitHandoff(epoch))
    }

    @Test
    fun anEarlierCapturesSignalDoesNotReleaseTheNextOne() = runTest {
        CaptureHandoff.begin()
        CaptureHandoff.contentShown()
        val epoch = CaptureHandoff.begin()
        val start = testScheduler.currentTime
        assertFalse(awaitHandoff(epoch))
        assertEquals(HANDOFF_CEILING_MS, testScheduler.currentTime - start)
    }
}
