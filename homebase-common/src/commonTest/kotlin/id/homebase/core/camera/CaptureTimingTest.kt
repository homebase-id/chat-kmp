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
        val sizes = listOf(1920 to 1440, 4032 to 3024, 8064 to 6048)
        assertEquals(4032 to 3024, choosePhotoSize(sizes))
    }

    @Test
    fun anOversizedOnlyFormatTakesItsSmallestStill() {
        assertEquals(5712 to 4284, choosePhotoSize(listOf(8064 to 6048, 5712 to 4284)))
        assertNull(choosePhotoSize(emptyList()))
    }

    @Test
    fun photoModeTakesTheRecordable4x3FormatWithTwelveMegapixelStills() {
        val twelveAndFortyEight = listOf(4032 to 3024, 8064 to 6048)
        val formats = listOf(
            VideoFormat(1920, 1080, 60.0, listOf(4032 to 2268)),
            VideoFormat(640, 480, 30.0, twelveAndFortyEight),
            VideoFormat(1440, 1080, 30.0, listOf(3264 to 2448)),
            VideoFormat(1920, 1440, 60.0, twelveAndFortyEight),
            VideoFormat(1920, 1440, 60.0, twelveAndFortyEight),
            VideoFormat(4032, 3024, 30.0, twelveAndFortyEight),
        )
        assertEquals(3, choosePhotoModeFormat(formats))
    }

    @Test
    fun photoModeHasNoFormatWithoutARecordable4x3One() {
        assertNull(choosePhotoModeFormat(listOf(VideoFormat(1920, 1080, 60.0, listOf(4032 to 2268)))))
        assertNull(choosePhotoModeFormat(listOf(VideoFormat(1920, 1440, 15.0, listOf(4032 to 3024)))))
        assertEquals(0, choosePhotoModeFormat(listOf(VideoFormat(1440, 1080, 30.0, listOf(3264 to 2448)))))
    }
}
