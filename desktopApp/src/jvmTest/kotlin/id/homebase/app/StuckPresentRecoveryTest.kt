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
    fun itNudgesUntilTheUiThreadRunsAgain() {
        val uiRan = CountDownLatch(1)
        var nudges = 0
        val rounds = StuckPresentRecovery.nudgeUntil(uiRan, retryMs = 0) { if (++nudges == 3) uiRan.countDown() }
        assertEquals(3, rounds)
    }

    @Test
    fun aFailingNudgeIsRetried() {
        val uiRan = CountDownLatch(1)
        var attempts = 0
        val rounds = StuckPresentRecovery.nudgeUntil(uiRan, retryMs = 0) { if (++attempts == 2) uiRan.countDown() else error("no display") }
        assertEquals(2, rounds)
    }

    @Test
    fun aUiThreadThatAlreadyRanIsNotNudged() {
        var nudges = 0
        assertEquals(0, StuckPresentRecovery.nudgeUntil(CountDownLatch(0), retryMs = 0) { nudges++ })
        assertEquals(0, nudges)
    }

    // Needs a real X server: runs on a Linux desktop, skipped on CI's headless runners.
    @Test
    fun theCanvasWindowsOfThisProcessAreResizedAndRestored() {
        if (!StuckPresentRecovery.onX11 || GraphicsEnvironment.isHeadless()) return
        lateinit var window: Frame
        val canvas = Canvas().apply { preferredSize = Dimension(320, 240) }
        EventQueue.invokeAndWait {
            window = Frame("StuckPresentRecoveryTest").apply {
                add(canvas)
                pack()
                isVisible = true
            }
        }
        try {
            val deadline = System.nanoTime() + 5_000_000_000L
            var nudged = 0
            while (nudged == 0 && System.nanoTime() < deadline) {
                nudged = StuckPresentRecovery.nudgeCanvases(ProcessHandle.current().pid())
                if (nudged == 0) Thread.sleep(100)
            }

            assertEquals(1, nudged)
            assertEquals(Dimension(320, 240), canvas.size)
        } finally {
            EventQueue.invokeAndWait { window.dispose() }
        }
    }
}
