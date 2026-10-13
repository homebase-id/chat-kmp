package id.homebase.chat.services.calling

import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCRtpSender
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import dev.onvoid.webrtc.media.video.CustomVideoSource
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CallSendResolutionLadderTest {
    private fun stats(target: Double?, sourceWidth: Int = 1080, sourceHeight: Int = 1920) = listOf(
        StatsEntry("media-source", mapOf("kind" to "video", "width" to sourceWidth, "height" to sourceHeight)),
        StatsEntry("outbound-rtp", mapOf("kind" to "video", "targetBitrate" to target, "frameWidth" to 1920, "frameHeight" to 1080)),
    )

    @Test
    fun rungsStepStraightDownAndBackUpOneAtATimeWithHeadroom() {
        assertEquals(1080, nextSendHeight(1080, 2_500_000.0))
        assertEquals(540, nextSendHeight(1080, 527_743.0))
        assertEquals(480, nextSendHeight(540, 300_000.0))
        assertEquals(480, nextSendHeight(480, 579_000.0))
        assertEquals(540, nextSendHeight(480, 881_000.0))
        assertEquals(720, nextSendHeight(540, 1_100_000.0))
        assertEquals(720, nextSendHeight(720, 1_600_000.0))
        assertEquals(1080, nextSendHeight(720, 2_500_000.0))
    }

    // mobile2-r1 Redmi under NLC 3G: target 2.5M -> 527k -> 300k, then 361k..881k..2.5M once NLC was off, while
    // libwebrtc kept 1920x1080 with resChanges=0 because the encoder's QP (25-26) never crossed the scaler's 37.
    @Test
    fun theDeviceRunsTargetsDriveTheRealSendersScaleBetween1080pAnd480p() {
        val factory = PeerConnectionFactory(HeadlessAudioDeviceModule())
        val source = CustomVideoSource()
        val peer = factory.createPeerConnection(RTCConfiguration(), object : PeerConnectionObserver {
            override fun onIceCandidate(candidate: RTCIceCandidate) = Unit
        })!!
        try {
            val sender = peer.addTrack(factory.createVideoTrack("video0", source), listOf("homebase-call"))
            configureVideoSender(sender)
            val ladder = SendResolutionLadder { scaleVideoSender(sender, it) }
            fun scale(target: Double) = ladder.onStats(stats(target)).let { sender.scale() }

            assertNull(ladder.onStats(stats(2_500_000.0)))
            assertEquals(2.0, scale(527_743.0))
            assertEquals(2.25, scale(300_000.0))
            assertEquals(2.25, scale(579_000.0))
            assertEquals(2.0, scale(881_000.0))
            assertEquals(1.5, scale(2_500_000.0))
            assertEquals(1.0, scale(2_500_000.0))
            assertNull(ladder.onStats(stats(null)))
        } finally {
            peer.close()
            source.dispose()
            factory.dispose()
        }
    }

    @Test
    fun theScaleIsRelativeToTheEncoderInputNotTheCamerasFourK() {
        var applied: Double? = null
        val ladder = SendResolutionLadder { applied = it }
        ladder.onStats(stats(300_000.0, sourceWidth = 3840, sourceHeight = 2160))
        assertEquals(2.25, applied)
        ladder.onStats(stats(2_500_000.0, sourceWidth = 1280, sourceHeight = 720))
        assertEquals(720.0 / 540, applied)
    }

    private fun RTCRtpSender.scale() = parameters.encodings.single().scaleResolutionDownBy

    // encoder-stall Redmi: the quality scaler's 960x540 at the 480 rung's 2.25 became 427x240, which MediaCodec refused.
    @Test
    fun theOddSizesLibwebrtcDerivesFromTheLaddersScalesAreRoundedDownToEven() {
        val scaled = (960 / 2.25).roundToInt()

        assertEquals(427, scaled)
        assertEquals(426, evenFloor(scaled))
        assertEquals(240, evenFloor(240))
        assertEquals(852, evenFloor((1920 / 2.25).roundToInt()))
    }
}
