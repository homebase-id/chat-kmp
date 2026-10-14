package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertTrue

internal const val H264_LEVEL_31 = 0x1f

internal fun h264Levels(sdp: String): List<Int> =
    Regex("profile-level-id=[0-9a-fA-F]{4}([0-9a-fA-F]{2})").findAll(sdp).map { it.groupValues[1].toInt(16) }.toList()

class CallSendVideoCapTest {
    @Test
    fun theCapFitsTheLevel31ThatLibwebrtcOffers() {
        val macroblocks = ((SendVideoCap.WIDTH + 15) / 16) * ((SendVideoCap.HEIGHT + 15) / 16)
        assertTrue(macroblocks <= 3600)
        assertTrue(macroblocks * SendVideoCap.FPS <= 108_000)
    }
}
