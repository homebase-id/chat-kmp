package id.homebase.app

import java.awt.Canvas
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.util.concurrent.atomic.AtomicBoolean
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
    fun anyOtherBlockIsLeftAlone() {
        assertFalse(StuckPresentRecovery.isStuckInSwap(arrayOf(frame("id.homebase.Foo", "readDatabase"))))
        assertFalse(StuckPresentRecovery.isStuckInSwap(arrayOf(frame("org.jetbrains.skiko.renderer.LinuxOpenGLRendererKt", "makeCurrent"))))
        assertFalse(StuckPresentRecovery.isStuckInSwap(emptyArray()))
        assertFalse(StuckPresentRecovery.isStuckInSwap(null))
    }

    @Test
    fun itNudgesUntilTheUiThreadRunsAgain() {
        val responded = AtomicBoolean(false)
        var nudges = 0
        val rounds = StuckPresentRecovery.nudgeUntil(responded, nudge = { nudges++; 1 }, pause = { if (nudges == 3) responded.set(true) })
        assertEquals(3, rounds)
        assertEquals(3, nudges)
    }

    @Test
    fun aFailingNudgeIsRetried() {
        val responded = AtomicBoolean(false)
        var attempts = 0
        val rounds = StuckPresentRecovery.nudgeUntil(responded, nudge = { attempts++; error("no display") }, pause = { if (attempts == 2) responded.set(true) })
        assertEquals(2, rounds)
    }

    @Test
    fun aUiThreadThatAlreadyRanIsNotNudged() {
        var nudges = 0
        assertEquals(0, StuckPresentRecovery.nudgeUntil(AtomicBoolean(true), nudge = { nudges++; 1 }, pause = {}))
        assertEquals(0, nudges)
    }

    // Needs a real X server: runs on a Linux desktop, skipped on CI's headless runners.
    @Test
    fun theCanvasWindowsOfThisProcessAreResizedAndRestored() {
        if (!System.getProperty("os.name").startsWith("Linux") || System.getenv("DISPLAY").isNullOrEmpty() || GraphicsEnvironment.isHeadless()) return
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
