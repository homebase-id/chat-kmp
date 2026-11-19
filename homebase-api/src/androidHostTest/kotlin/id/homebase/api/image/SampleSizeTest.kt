package id.homebase.api.image

import kotlin.test.Test
import kotlin.test.assertEquals

class SampleSizeTest {
    @Test
    fun theJpegDecoderLeavesLanczosAtLeastTwoTimes() {
        // 12 MP to 2048: under 2x, a full decode.
        assertEquals(1, sampleSizeFor(4032, 2048))
        // 48 MP to 2048 (3.9x): a full decode, so the block shrink doesn't alias what Lanczos can't remove.
        assertEquals(1, sampleSizeFor(8064, 2048))
        // Exactly 4x: halve, and Lanczos makes the last 2x.
        assertEquals(2, sampleSizeFor(8192, 2048))
        assertEquals(2, sampleSizeFor(8064, 1080))
        // 48 MP to the 320 thumbnail (25x): shrink by 8, leaving 3.15x.
        assertEquals(8, sampleSizeFor(8064, 320))
    }
}
