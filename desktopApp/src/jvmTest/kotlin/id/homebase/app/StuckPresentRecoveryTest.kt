package id.homebase.app

import java.awt.Canvas
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class StuckPresentRecoveryTest {

    private fun frame(className: String, method: String) = StackTraceElement(className, method, null, -2)

    @Test
    fun aUiThreadWaitingInSkikosLinuxSwapIsStuck() {
        val stack = arrayOf(
            frame("org.jetbrains.skiko.renderer.LinuxOpenGLRendererKt", "swapBuffers"),
            frame("org.jetbrains.skiko.renderer.LinuxGLFrameBatch", "drawAndPresent"),
        )
        assertTrue(StuckPresentRecovery.isStuckInSwap(stack))
    }

    @Test
    fun skikoStillHasTheSwapTheRecoveryWatchesFor() {
        assertTrue(Class.forName(StuckPresentRecovery.SWAP_CLASS).declaredMethods.any { it.name == StuckPresentRecovery.SWAP_METHOD })
    }

    @Test
    fun anyOtherBlockIsLeftAlone() {
        assertFalse(StuckPresentRecovery.isStuckInSwap(arrayOf(frame("id.homebase.Foo", "readDatabase"))))
        assertFalse(StuckPresentRecovery.isStuckInSwap(arrayOf(frame("org.jetbrains.skiko.renderer.LinuxOpenGLRendererKt", "makeCurrent"))))
        assertFalse(StuckPresentRecovery.isStuckInSwap(emptyArray()))
    }

    @Test
    fun itNudgesWhileStuckUntilTheUiThreadRunsAgain() {
        val uiRan = CountDownLatch(1)
        var nudges = 0
        val sent = StuckPresentRecovery.nudgeWhileStuck(uiRan, retryMs = 0, stuck = { true }) { if (++nudges == 3) uiRan.countDown() }
        assertEquals(3, sent)
    }

    @Test
    fun aUiThreadBusyElsewhereIsNotNudgedUntilItReachesTheSwap() {
        val uiRan = CountDownLatch(1)
        var checks = 0
        var nudges = 0
        val sent = StuckPresentRecovery.nudgeWhileStuck(uiRan, retryMs = 0, stuck = { ++checks > 2 }) { nudges++; uiRan.countDown() }
        assertEquals(1, sent)
        assertEquals(3, checks)
    }

    @Test
    fun aFailingNudgeIsRetried() {
        val uiRan = CountDownLatch(1)
        var attempts = 0
        val sent = StuckPresentRecovery.nudgeWhileStuck(uiRan, retryMs = 0, stuck = { true }) { if (++attempts == 2) uiRan.countDown() else error("no display") }
        assertEquals(2, sent)
    }

    @Test
    fun aMissingNativeLibraryStopsTheRounds() {
        val sent = StuckPresentRecovery.nudgeWhileStuck(CountDownLatch(1), retryMs = 0, stuck = { true }) { throw UnsatisfiedLinkError("libX11") }
        assertEquals(1, sent)
    }

    @Test
    fun aUiThreadThatAlreadyRanIsNotNudged() {
        var nudges = 0
        assertEquals(0, StuckPresentRecovery.nudgeWhileStuck(CountDownLatch(0), retryMs = 0, stuck = { true }) { nudges++ })
        assertEquals(0, nudges)
    }

    @Test
    fun theCanvasWindowsOfThisProcessAreResizedAndRestored() {
        assumeTrue("needs a real X server", StuckPresentRecovery.onX11 && !GraphicsEnvironment.isHeadless())
        val pid = ProcessHandle.current().pid()
        lateinit var window: Frame
        EventQueue.invokeAndWait {
            window = Frame("StuckPresentRecoveryTest").apply {
                add(Canvas().apply { preferredSize = Dimension(320, 240) })
                pack()
                isVisible = true
            }
        }
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            var before = StuckPresentRecovery.canvasSizes(pid)
            while (before.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(100)
                before = StuckPresentRecovery.canvasSizes(pid)
            }

            assertEquals(1, StuckPresentRecovery.nudgeCanvases(pid))
            assertEquals(before, StuckPresentRecovery.canvasSizes(pid))
        } finally {
            EventQueue.invokeAndWait { window.dispose() }
        }
    }
}
