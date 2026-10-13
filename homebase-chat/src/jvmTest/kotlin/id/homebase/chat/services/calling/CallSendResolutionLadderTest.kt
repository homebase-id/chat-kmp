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

class CallSendResolutionLadderTest {
    private fun stats(target: Double?, sourceWidth: Int = 1080, sourceHeight: Int = 1920) = listOf(
        StatsEntry("media-source", mapOf("kind" to "video", "width" to sourceWidth, "height" to sourceHeight)),
        StatsEntry("outbound-rtp", mapOf("kind" to "video", "targetBitrate" to target, "frameWidth" to 1920, "frameHeight" to 1080)),
    )

    @Test
    fun rungsStepStraightDownAndBackUpOneAtATimeWithHeadroom() {
        assertEquals(1080, nextSendHeight(1080, 2_500_000.0))
        assertEquals(540, nextSendHeight(1080, 527_743.0))
        assertEquals(360, nextSendHeight(540, 300_000.0))
        assertEquals(360, nextSendHeight(360, 579_000.0))
        assertEquals(540, nextSendHeight(360, 881_000.0))
        assertEquals(720, nextSendHeight(540, 1_100_000.0))
        assertEquals(720, nextSendHeight(720, 1_600_000.0))
        assertEquals(1080, nextSendHeight(720, 2_500_000.0))
        assertEquals(270, nextSendHeight(360, 200_000.0))
        assertEquals(180, nextSendHeight(270, 100_000.0))
        assertEquals(270, nextSendHeight(180, 190_000.0))
    }

    @Test
    fun everyScaleKeepsBothSidesEven() {
        assertEquals(468, evenShortSide(480, 1080, 1920))
        for (rung in listOf(1080, 720, 540, 360, 270, 180)) assertEquals(rung, evenShortSide(rung, 1080, 1920))
        for (rung in listOf(720, 540, 360, 270, 180)) assertEquals(rung, evenShortSide(rung, 720, 1280))
    }

    @Test
    fun theLadderGoesBelow360WithEvenSidesOnEitherCamera() {
        var applied: Double? = null
        val hd = SendResolutionLadder { applied = it }
        hd.onStats(stats(100_000.0))
        assertEquals(6.0, applied)
        val small = SendResolutionLadder { applied = it }
        small.onStats(stats(100_000.0, sourceWidth = 1280, sourceHeight = 720))
        assertEquals(4.0, applied)
    }

    // mobile2-r1 Redmi under NLC 3G: target 2.5M -> 527k -> 300k, then 361k..881k..2.5M once NLC was off, while
    // libwebrtc kept 1920x1080 with resChanges=0 because the encoder's QP (25-26) never crossed the scaler's 37.
    @Test
    fun theDeviceRunsTargetsDriveTheRealSendersScaleBetween1080pAnd360p() {
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

    private class Encoder(var width: Int = 1920, var height: Int = 1080) {
        private var frames = 0.0
        private var seconds = 0.0

        fun tick(fps: Double, encodeMs: Double): Map<String, Any?> {
            val count = fps * 5
            frames += count
            seconds += count * encodeMs / 1000
            return mapOf(
                "kind" to "video", "framesPerSecond" to fps, "framesEncoded" to frames, "totalEncodeTime" to seconds,
                "frameWidth" to width, "frameHeight" to height,
            )
        }
    }

    // Owner call 14:30-14:31 Redmi sender: 13-22 fps, 61-108 ms a frame at 360p-720p.
    @Test
    fun aSenderThatCannotEncode30FpsStepsDownARungEveryTwoSlowTicks() {
        val rung = SendLoadRung()
        val encoder = Encoder(1280, 720)
        assertEquals(1080, rung.onStats(encoder.tick(19.0, 79.3)))
        assertEquals(540, rung.onStats(encoder.tick(22.0, 88.7)))
        encoder.width = 960; encoder.height = 540
        assertEquals(540, rung.onStats(encoder.tick(18.0, 90.7)))
        assertEquals(360, rung.onStats(encoder.tick(13.0, 61.0)))
    }

    // 13:56 Redmi in dim light: the camera gave 15 fps and each 270p frame took 12-14 ms.
    @Test
    fun aCameraSlowedByLowLightIsNotAnOverloadedEncoder() {
        val rung = SendLoadRung()
        val encoder = Encoder(480, 270)
        repeat(6) { assertEquals(1080, rung.onStats(encoder.tick(15.0, 13.5))) }
    }

    @Test
    fun itStepsBackUpOnlyWhenTheNextRungsPixelsWouldStillEncodeInTime() {
        val rung = SendLoadRung()
        val encoder = Encoder(1280, 720)
        rung.onStats(encoder.tick(12.0, 70.0))
        assertEquals(540, rung.onStats(encoder.tick(12.0, 70.0)))
        encoder.width = 960; encoder.height = 540
        repeat(5) { assertEquals(540, rung.onStats(encoder.tick(30.0, 15.0))) }
        assertEquals(720, rung.onStats(encoder.tick(30.0, 15.0)))
        encoder.width = 1280; encoder.height = 720
        // 1080p would need 25 ms x 2.25 = 56 ms a frame: stay.
        repeat(12) { assertEquals(720, rung.onStats(encoder.tick(30.0, 25.0))) }
    }

    @Test
    fun theLoadRungCapsTheLaddersScaleBelowTheNetworkFloor() {
        var applied: Double? = null
        val ladder = SendResolutionLadder { applied = it }
        val encoder = Encoder(1280, 720)
        fun stats(fps: Double, ms: Double) = listOf(
            StatsEntry("media-source", mapOf("kind" to "video", "width" to 1080, "height" to 1920)),
            StatsEntry("outbound-rtp", encoder.tick(fps, ms) + ("targetBitrate" to 300_000.0)),
        )
        ladder.onStats(stats(14.0, 80.0))
        assertEquals(3.0, applied)
        encoder.width = 640; encoder.height = 360
        ladder.onStats(stats(14.0, 80.0))
        assertEquals(4.0, applied)
    }

    private fun RTCRtpSender.scale() = parameters.encodings.single().scaleResolutionDownBy
}
