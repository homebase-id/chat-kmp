package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CallDecoderResetTest {
    @Test
    fun aFrameAtANewSizeResetsTheDecoder() {
        assertTrue(decoderNeedsReset(1280, 720, 960, 540))
        assertTrue(decoderNeedsReset(1280, 720, 1280, 540))
        assertTrue(decoderNeedsReset(1280, 720, 1920, 720))
    }

    @Test
    fun aFrameAtTheInitSizeOrWithNoSizeLeavesItAlone() {
        assertFalse(decoderNeedsReset(1280, 720, 1280, 720))
        assertFalse(decoderNeedsReset(1280, 720, 0, 0))
        assertFalse(decoderNeedsReset(1280, 720, 0, 720))
        assertFalse(decoderNeedsReset(1280, 720, 1280, 0))
    }
}
