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

    private class Sender {
        private val clock = TestTimeSource()
        var applied: Double? = null
        val ladder = SendResolutionLadder(clock) { applied = it }
        private var bytes = 0.0
        val rung get() = applied?.let { (1080 / it).toInt() } ?: 1080

        fun tick(target: Double, sentBps: Double, rtt: Double, frameShort: Int = rung, limit: String = "none"): Int {
            clock += 5.seconds
            bytes += sentBps * 5 / 8
            ladder.onStats(
                listOf(
                    StatsEntry("media-source", mapOf("kind" to "video", "width" to 1920, "height" to 1080)),
                    StatsEntry(
                        "outbound-rtp",
                        mapOf(
                            "kind" to "video", "targetBitrate" to target, "bytesSent" to bytes, "frameWidth" to frameShort * 16 / 9,
                            "frameHeight" to frameShort, "qualityLimitationReason" to limit,
                        ),
                    ),
                    StatsEntry("candidate-pair", mapOf("nominated" to true, "state" to "succeeded", "currentRoundTripTime" to rtt)),
                ),
            )
            return rung
        }

        fun dropTo360() {
            tick(2_500_000.0, 2_100_000.0, 0.015)
            assertEquals(360, tick(300_000.0, 1_400_000.0, 0.015))
        }
    }

    // iPhone sender, NLC Edge 20:17:01-20:18:26: after Edge the 360p picture sent 140-197 kbps, goog-cc's target
    // crept 371k-414k for 3 minutes and the 540p rung wanted 625k, so the call stayed at 360p until it ended.
    @Test
    fun afterEdgeTheLadderClimbsBackFrom360pThoughTheTargetNeverReachesTheNextRung() {
        val iphone = Sender()
        assertEquals(1080, iphone.tick(2_500_000.0, 2_143_000.0, 0.02))
        assertEquals(1080, iphone.tick(2_500_000.0, 2_051_000.0, 0.013))
        assertEquals(180, iphone.tick(142_064.0, 1_400_000.0, 0.014))
        assertEquals(180, iphone.tick(142_064.0, 543_000.0, 2.517))
        assertEquals(180, iphone.tick(142_064.0, 219_000.0, 2.627))
        assertEquals(270, iphone.tick(268_361.0, 124_000.0, 2.199))
        for ((target, sent) in listOf(240_702.0 to 231_000.0, 182_744.0 to 233_000.0, 204_061.0 to 202_000.0, 284_128.0 to 249_000.0)) {
            assertEquals(270, iphone.tick(target, sent, 2.287))
        }
        assertEquals(270, iphone.tick(284_128.0, 188_000.0, 0.016))
        assertEquals(360, iphone.tick(316_629.0, 165_000.0, 0.015))
        assertEquals(360, iphone.tick(371_354.0, 191_000.0, 0.015))
        assertEquals(360, iphone.tick(371_354.0, 154_000.0, 0.017))
        assertEquals(540, iphone.tick(371_354.0, 152_000.0, 0.02))

        // Beyond tonight's log: goog-cc follows what is sent, about 2.6x it as it did at 360p.
        repeat(2) { assertEquals(540, iphone.tick(780_000.0, 300_000.0, 0.015)) }
        assertEquals(720, iphone.tick(780_000.0, 300_000.0, 0.015))
        repeat(2) { assertEquals(720, iphone.tick(1_170_000.0, 450_000.0, 0.015)) }
        assertEquals(1080, iphone.tick(1_170_000.0, 450_000.0, 0.015))
    }

    @Test
    fun aClimbTheLinkCannotCarryFallsBackAndTheNextTryWaitsTwiceAsLong() {
        val sender = Sender()
        sender.dropTo360()
        repeat(2) { assertEquals(360, sender.tick(414_721.0, 160_000.0, 0.015)) }
        assertEquals(540, sender.tick(414_721.0, 160_000.0, 0.015))
        assertEquals(540, sender.tick(430_000.0, 330_000.0, 0.015))
        assertEquals(360, sender.tick(430_000.0, 420_000.0, 0.015))
        repeat(5) { assertEquals(360, sender.tick(430_000.0, 160_000.0, 0.015)) }
        assertEquals(540, sender.tick(430_000.0, 160_000.0, 0.015))
    }

    // 66ca91774: with libwebrtc already shedding resolution (limit=bandwidth, 853x480 of its own) the old
    // encoder-limited climb flapped 360p-540p-720p every 15 s.
    @Test
    fun itDoesNotClimbOnABusyLinkOrWhileLibwebrtcShedsResolutionItself() {
        val sender = Sender()
        sender.dropTo360()
        repeat(8) { assertEquals(360, sender.tick(414_721.0, 160_000.0, 2.4)) }
        repeat(8) { assertEquals(360, sender.tick(414_721.0, 160_000.0, 0.015, limit = "bandwidth")) }
        repeat(8) { assertEquals(360, sender.tick(414_721.0, 160_000.0, 0.015, frameShort = 270)) }
    }

    @Test
    fun aSenderUnderTargetHoldsItsRungWhenTheTargetDipsBelowIt() {
        val sender = Sender()
        sender.tick(2_500_000.0, 900_000.0, 0.015)
        assertEquals(1080, sender.tick(1_100_000.0, 600_000.0, 0.015))
        assertEquals(720, sender.tick(1_100_000.0, 1_000_000.0, 0.015))
    }
}
