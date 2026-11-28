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
        val sent = StuckPresentRecovery.nudgeWhileStuck(uiRan, wait = {}, stuck = { true }) { if (++nudges == 3) uiRan.countDown(); 1 }
        assertEquals(3, sent)
    }

    @Test
    fun aUiThreadBusyElsewhereIsNotNudgedUntilItReachesTheSwap() {
        val uiRan = CountDownLatch(1)
        var checks = 0
        var nudges = 0
        val sent = StuckPresentRecovery.nudgeWhileStuck(uiRan, wait = {}, stuck = { ++checks > 2 }) { nudges++; uiRan.countDown(); 1 }
        assertEquals(1, sent)
        assertEquals(3, checks)
    }

    @Test
    fun aFailingNudgeIsRetried() {
        val uiRan = CountDownLatch(1)
        var attempts = 0
        val sent = StuckPresentRecovery.nudgeWhileStuck(uiRan, wait = {}, stuck = { true }) { if (++attempts == 2) uiRan.countDown() else error("no display"); 1 }
        assertEquals(1, sent)
    }

    @Test
    fun aFailingStuckCheckIsRetried() {
        val uiRan = CountDownLatch(1)
        var checks = 0
        val sent = StuckPresentRecovery.nudgeWhileStuck(uiRan, wait = {}, stuck = { if (++checks == 1) throw IllegalStateException() else true }) { uiRan.countDown(); 1 }
        assertEquals(1, sent)
        assertEquals(2, checks)
    }

    @Test
    fun aMissingNativeLibraryStopsTheRounds() {
        val sent = StuckPresentRecovery.nudgeWhileStuck(CountDownLatch(1), wait = {}, stuck = { true }) { throw UnsatisfiedLinkError("libX11") }
        assertEquals(0, sent)
    }

    @Test
    fun aRoundThatResizesNothingIsNotCounted() {
        val uiRan = CountDownLatch(1)
        var rounds = 0
        val sent = StuckPresentRecovery.nudgeWhileStuck(uiRan, wait = {}, stuck = { true }) { if (++rounds == 3) uiRan.countDown(); if (rounds == 3) 1 else 0 }
        assertEquals(1, sent)
    }

    @Test
    fun theNudgesBackOffFromOneSecondToEight() {
        val uiRan = CountDownLatch(1)
        val waits = mutableListOf<Long>()
        StuckPresentRecovery.nudgeWhileStuck(uiRan, stuck = { true }, wait = { waits += it; if (waits.size == 6) uiRan.countDown() }) { 1 }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 8_000L, 8_000L), waits)
    }

    @Test
    fun roundsThatSentNothingDontGrowTheWait() {
        val uiRan = CountDownLatch(1)
        val waits = mutableListOf<Long>()
        var checks = 0
        StuckPresentRecovery.nudgeWhileStuck(uiRan, stuck = { ++checks > 3 }, wait = { waits += it; if (waits.size == 6) uiRan.countDown() }) { 1 }
        assertEquals(listOf(1_000L, 1_000L, 1_000L, 1_000L, 2_000L, 4_000L), waits)
    }

    @Test
    fun aUiThreadThatAlreadyRanIsNotNudged() {
        var nudges = 0
        assertEquals(0, StuckPresentRecovery.nudgeWhileStuck(CountDownLatch(0), wait = {}, stuck = { true }) { nudges++; 1 })
        assertEquals(0, nudges)
    }

    @Test
    fun theCanvasWindowsOfThisProcessAreResizedAndRestored() {
        assumeTrue("needs XWayland", StuckPresentRecovery.onXWayland && !GraphicsEnvironment.isHeadless())
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
            // Settled: the window manager may still place or resize the frame right after it maps.
            val deadline = System.nanoTime() + 5_000_000_000L
            var previous = emptyList<Pair<Int, Int>>()
            var before = StuckPresentRecovery.canvasSizes(pid)
            while ((before.isEmpty() || before != previous) && System.nanoTime() < deadline) {
                Thread.sleep(100)
                previous = before
                before = StuckPresentRecovery.canvasSizes(pid)
            }
            var during = emptyList<Pair<Int, Int>>()

            assertEquals(1, StuckPresentRecovery.nudgeCanvases(pid) { during = StuckPresentRecovery.canvasSizes(pid) })
            assertEquals(before.map { (w, h) -> w to h - 1 }, during)
            assertEquals(before, StuckPresentRecovery.canvasSizes(pid))
        } finally {
            EventQueue.invokeAndWait { window.dispose() }
        }
    }
}
