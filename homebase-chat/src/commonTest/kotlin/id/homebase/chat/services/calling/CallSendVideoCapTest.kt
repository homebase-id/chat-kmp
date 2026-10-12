package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertTrue

class CallSendVideoCapTest {
    private val level31MaxMacroblocks = 3600

    @Test
    fun capFitsTheNegotiatedH264Level() {
        assertTrue(h264MacroblocksPerFrame(SendVideoCap.WIDTH, SendVideoCap.HEIGHT) <= level31MaxMacroblocks)
    }

    @Test
    fun theFrameVideoToolboxRejectedExceedsTheLevel() {
        assertTrue(h264MacroblocksPerFrame(1920, 1080) > level31MaxMacroblocks)
    }
}
