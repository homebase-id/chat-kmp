package id.homebase.chat.services.calling

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal fun h264MacroblocksPerFrame(width: Int, height: Int): Int = ((width + 15) / 16) * ((height + 15) / 16)

internal fun h264Levels(sdp: String): List<Int> =
    Regex("profile-level-id=[0-9a-fA-F]{4}([0-9a-fA-F]{2})").findAll(sdp).map { it.groupValues[1].toInt(16) }.toList()

class CallSendVideoCapTest {
    private val level31MaxMacroblocks = 3600
    private val level40MaxMacroblocks = 8192
    private val level40MaxMacroblocksPerSecond = 245_760

    @Test
    fun theCapNeedsMoreThanLevel31AndFitsLevel40() {
        val frame = h264MacroblocksPerFrame(SendVideoCap.WIDTH, SendVideoCap.HEIGHT)
        assertTrue(frame > level31MaxMacroblocks)
        assertTrue(frame <= level40MaxMacroblocks)
        assertTrue(frame * SendVideoCap.FPS <= level40MaxMacroblocksPerSecond)
        assertEquals(0x28, SendVideoCap.H264_MIN_LEVEL)
    }

    // The fmtp lines libwebrtc 125 writes on Android and iOS; the iPhone r2 encoder failed on profile-level-id=42e01f.
    private val offer = listOf(
        "m=video 9 UDP/TLS/RTP/SAVPF 96 98 100 102",
        "a=rtpmap:96 VP8/90000",
        "a=rtpmap:98 H264/90000",
        "a=fmtp:98 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=42e01f",
        "a=rtpmap:100 H264/90000",
        "a=fmtp:100 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=640c1f",
        "a=rtpmap:102 H264/90000",
        "a=fmtp:102 level-asymmetry-allowed=1;packetization-mode=1;profile-level-id=640c34",
        "a=fmtp:97 apt=96",
        "",
    ).joinToString("\r\n")

    @Test
    fun everyH264LevelBelow40IsRaisedAndProfilesAndHigherLevelsAreKept() {
        val raised = withH264SendLevel(offer)
        assertEquals(listOf(0x1f, 0x1f, 0x34), h264Levels(offer))
        assertEquals(listOf(0x28, 0x28, 0x34), h264Levels(raised))
        assertTrue("profile-level-id=42e028" in raised)
        assertTrue("profile-level-id=640c28" in raised)
        assertEquals(offer.lines().size, raised.lines().size)
        assertEquals(offer.replace("42e01f", "42e028").replace("640c1f", "640c28"), raised)
    }

    @Test
    fun anSdpWithoutH264IsUnchanged() {
        val vp8 = "m=video 9 UDP/TLS/RTP/SAVPF 96\r\na=rtpmap:96 VP8/90000\r\n"
        assertEquals(vp8, withH264SendLevel(vp8))
    }
}
