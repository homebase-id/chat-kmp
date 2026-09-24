package id.homebase.core.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CaptureTimingTest {
    @Test
    fun theTimerHasNoStartUntilASampleIsWritten() {
        assertNull(recordingStartedAtMs(nowMs = 10_000L, recordedDurationNanos = 0L))
    }

    @Test
    fun theTimerStartsWhenTheFirstSampleWasWritten() {
        assertEquals(9_750L, recordingStartedAtMs(nowMs = 10_000L, recordedDurationNanos = 250_000_000L))
    }

    @Test
    fun photosCapAtTwelveMegapixels() {
        val sizes = listOf(1920 to 1080, 4032 to 2268, 8064 to 4536)
        assertEquals(4032 to 2268, choosePhotoSize(sizes))
    }

    @Test
    fun anOversizedOnlyFormatTakesItsSmallestStill() {
        assertEquals(5712 to 4284, choosePhotoSize(listOf(8064 to 6048, 5712 to 4284)))
        assertNull(choosePhotoSize(emptyList()))
    }
}
