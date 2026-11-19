package id.homebase.api.image

import kotlin.test.Test
import kotlin.test.assertEquals

class SampleSizeTest {
    private fun sample(width: Int, height: Int, minLongEdge: Int) = sampleSizeFor(ImageSize(width, height), minLongEdge)

    @Test
    fun theJpegDecoderLeavesLanczosAtLeastTwoTimes() {
        // 12 MP to 2048: under 2x, a full decode.
        assertEquals(1, sample(4032, 3024, 2048))
        // 48 MP to 2048 (3.9x): a full decode, so the block shrink doesn't alias what Lanczos can't remove.
        assertEquals(1, sample(8064, 6048, 2048))
        // Exactly 4x: halve, and Lanczos makes the last 2x.
        assertEquals(2, sample(8192, 6144, 2048))
        assertEquals(2, sample(8064, 6048, 1080))
        // 48 MP to the 320 thumbnail (25x): shrink by 8, leaving 3.15x.
        assertEquals(8, sample(8064, 6048, 320))
    }

    @Test
    fun overTheBudgetItHalvesAsBeforeButNeverBelowTheOutput() {
        // 50 MP is still a full decode; 64 MP and 200 MP halve until they fit, as the decoder always did.
        assertEquals(1, sample(8160, 6120, 2048))
        assertEquals(2, sample(9248, 6936, 2048))
        assertEquals(2, sample(16320, 12240, 4096))
        // 200 MP to 2048 (8x): one halving already fits the budget, and leaves Lanczos 4x.
        assertEquals(2, sample(16320, 12240, 2048))
        // Over the budget, a 100 MP panorama halves to 10000, still above its 8192 output...
        assertEquals(2, sample(20000, 5000, 8192))
        // ...but never below it: halving 90 MP would give 7500, so it decodes in full.
        assertEquals(1, sample(15000, 6000, 8192))
    }

    @Test
    fun itNeverSamplesPastEight() {
        // Past 8 the decoder skips pixels after its DCT 1/8.
        assertEquals(8, sample(4032, 3024, 20))
        assertEquals(8, sample(16320, 12240, 20))
    }
}
