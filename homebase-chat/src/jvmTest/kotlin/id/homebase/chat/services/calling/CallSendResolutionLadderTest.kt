package id.homebase.chat.services.calling

import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.PeerConnectionObserver
import dev.onvoid.webrtc.RTCConfiguration
import dev.onvoid.webrtc.RTCIceCandidate
import dev.onvoid.webrtc.RTCRtpSender
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import dev.onvoid.webrtc.media.video.CustomVideoSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class CallSendResolutionLadderTest {
    private fun stats(target: Double?, sourceWidth: Int = 1080, sourceHeight: Int = 1920, bytesSent: Long? = null) = listOf(
        StatsEntry("media-source", mapOf("kind" to "video", "width" to sourceWidth, "height" to sourceHeight)),
        StatsEntry(
            "outbound-rtp",
            mapOf("kind" to "video", "targetBitrate" to target, "bytesSent" to bytesSent, "frameWidth" to 1920, "frameHeight" to 1080),
        ),
    )

    @Test
    fun rungsStepStraightDownAndBackUpOneAtATimeWithHeadroom() {
        assertEquals(1080, nextSendHeight(1080, 2_500_000.0))
        assertEquals(540, nextSendHeight(1080, 527_743.0))
        assertEquals(360, nextSendHeight(540, 300_000.0))
        assertEquals(360, nextSendHeight(360, 579_000.0))
        assertEquals(540, nextSendHeight(360, 881_000.0))
        assertEquals(180, nextSendHeight(540, 40_000.0))
        assertEquals(270, nextSendHeight(180, 200_000.0))
        assertEquals(180, nextSendHeight(270, 120_000.0))
        assertEquals(720, nextSendHeight(540, 1_100_000.0))
        assertEquals(720, nextSendHeight(720, 1_600_000.0))
        assertEquals(1080, nextSendHeight(720, 2_500_000.0))
    }

    @Test
    fun anEncoderSendingFarUnderTargetStepsUpWithoutTheHeadroomItsEstimateCannotReach() {
        assertEquals(720, nextSendHeight(720, 1_752_508.0))
        assertEquals(1080, nextSendHeight(720, 1_752_508.0, sentBps = 800_000.0))
        assertEquals(720, nextSendHeight(720, 1_752_508.0, sentBps = 1_400_000.0))
        assertEquals(720, nextSendHeight(720, 1_400_000.0, sentBps = 300_000.0))
        assertEquals(180, nextSendHeight(180, 85_580.0, sentBps = 26_000.0))
    }

    // nofloor iPhone after NLC Edge: 1280x720 at QP 25 sent 0.7-0.93 Mbps for 4.5 min while the target sat at 1.75-1.80M.
    @Test
    fun theStalledSevenTwentyPlateauClimbsToTenEighty() {
        val clock = TestTimeSource()
        var applied: Double? = null
        val ladder = SendResolutionLadder(clock) { applied = it }
        var bytes = 0L
        fun tick(target: Double, sentBps: Long): String? {
            clock += 5.seconds
            bytes += sentBps * 5 / 8
            return ladder.onStats(stats(target, bytesSent = bytes))
        }
        tick(1_048_225.0, 560_000)
        assertEquals(1.5, applied)
        assertEquals("send ladder target=1752508 sent=720000 height=1080 scaleDownBy=1.0", tick(1_752_508.0, 720_000))
        assertEquals(1.0, applied)
    }

    // mobile2-r1 Redmi under NLC 3G: target 2.5M -> 527k -> 300k, then 361k..881k..2.5M once NLC was off, while
    // libwebrtc kept 1920x1080 with resChanges=0 because the encoder's QP (25-26) never crossed the scaler's 37.
    @Test
    fun theDeviceRunsTargetsDriveTheRealSendersScaleBetween1080pAnd180p() {
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
            assertEquals(3.0, scale(300_000.0))
            assertEquals(3.0, scale(579_000.0))
            assertEquals(6.0, scale(40_000.0))
            assertEquals(4.0, scale(881_000.0))
            assertEquals(3.0, scale(881_000.0))
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
        assertEquals(3.0, applied)
        ladder.onStats(stats(2_500_000.0, sourceWidth = 1280, sourceHeight = 720))
        assertEquals(720.0 / 540, applied)
    }

    private fun RTCRtpSender.scale() = parameters.encodings.single().scaleResolutionDownBy
}
