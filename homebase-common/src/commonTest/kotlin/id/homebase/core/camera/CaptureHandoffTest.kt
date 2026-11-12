package id.homebase.core.camera

import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CaptureHandoffTest {
    @Test
    fun cameraWaitsForTheReceiverToDrawTheCapture() = runTest {
        val handoff = CaptureHandoff()
        val epoch = handoff.begin()
        val waiting = async { awaitHandoff(handoff, epoch) }
        advanceTimeBy(HANDOFF_CEILING_MS - 100)
        runCurrent()
        assertFalse(waiting.isCompleted)

        handoff.contentShown()
        runCurrent()
        assertTrue(waiting.await())
    }

    @Test
    fun aSignalThatLandsBeforeTheWaitStillCounts() = runTest {
        val handoff = CaptureHandoff()
        val epoch = handoff.begin()
        handoff.contentShown()
        assertTrue(awaitHandoff(handoff, epoch))
    }

    @Test
    fun anEarlierCapturesSignalDoesNotReleaseTheNextOne() = runTest {
        val handoff = CaptureHandoff()
        handoff.begin()
        handoff.contentShown()
        val epoch = handoff.begin()
        val start = testScheduler.currentTime
        assertFalse(awaitHandoff(handoff, epoch))
        assertEquals(HANDOFF_CEILING_MS, testScheduler.currentTime - start)
    }

    @Test
    fun anotherLaunchersSignalDoesNotReleaseThisOne() = runTest {
        val mine = InAppCameraLauncher()
        val other = InAppCameraLauncher()
        val epoch = mine.handoff.begin()
        other.resultShown()
        val start = testScheduler.currentTime
        assertFalse(awaitHandoff(mine.handoff, epoch))
        assertEquals(HANDOFF_CEILING_MS, testScheduler.currentTime - start)

        val epoch2 = mine.handoff.begin()
        mine.resultShown()
        assertTrue(awaitHandoff(mine.handoff, epoch2))
    }

    @Test
    fun theWarmedEngineIsReleasedOnceByTheLauncherThatOwnsIt() {
        val engine = FakeCameraEngine()
        val launcher = InAppCameraLauncher()
        launcher.warmer = CameraWarmer { engine }
        launcher.launch()
        assertSame(engine, launcher.warmEngine)
        assertEquals(0, engine.releaseCount)

        launcher.close()
        launcher.close()
        launcher.releaseWarmEngine()
        assertEquals(1, engine.releaseCount)
        assertNull(launcher.warmEngine)
    }
}
