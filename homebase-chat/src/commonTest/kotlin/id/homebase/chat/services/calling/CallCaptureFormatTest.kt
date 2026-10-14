package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CallCaptureFormatTest {
    private fun f(w: Int, h: Int, fps: Double, pix: String = "420v", binned: Boolean = false) =
        CaptureFormat(w, h, fps, pix, binned)

    // Shaped like an iPhone front camera's list, ascending as AVFoundation returns it.
    private val iphone = listOf(
        f(640, 480, 30.0), f(640, 480, 30.0, "420f"),
        f(1280, 720, 60.0, "420v"), f(1280, 720, 30.0, "420f", binned = true),
        f(1280, 720, 30.0, "x420", binned = true),
        f(1920, 1080, 30.0, "420f"), f(1920, 1080, 60.0, "420f"),
        f(3840, 2160, 30.0, "420f"), f(3840, 2160, 30.0, "x420"),
    )

    @Test
    fun picksTheBinned720pThatReaches30NotTheSensorMaximum() {
        assertEquals(f(1280, 720, 30.0, "420f", binned = true), chooseCaptureFormat(iphone))
    }

    @Test
    fun skipsTenBitAndSlowFormats() {
        val formats = listOf(f(1280, 720, 30.0, "x420"), f(1280, 720, 24.0), f(1920, 1080, 30.0))
        assertEquals(f(1920, 1080, 30.0), chooseCaptureFormat(formats))
    }

    @Test
    fun without720pTakesTheSmallestThatCoversIt() {
        val formats = listOf(f(3840, 2160, 30.0), f(640, 480, 30.0), f(1920, 1440, 30.0), f(1920, 1080, 30.0))
        assertEquals(f(1920, 1080, 30.0), chooseCaptureFormat(formats))
    }

    @Test
    fun amongEqualsPrefersTheLowestMaxFps() {
        val formats = listOf(f(1280, 720, 240.0), f(1280, 720, 60.0), f(1280, 720, 120.0))
        assertEquals(f(1280, 720, 60.0), chooseCaptureFormat(formats))
    }

    @Test
    fun nothingUsableLeavesTheCapturersChoice() {
        assertNull(chooseCaptureFormat(listOf(f(640, 480, 30.0), f(3840, 2160, 30.0, "x420"))))
    }
}
