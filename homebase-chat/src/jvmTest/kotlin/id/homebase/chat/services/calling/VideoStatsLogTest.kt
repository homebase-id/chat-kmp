package id.homebase.chat.services.calling

import java.math.BigInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VideoStatsLogTest {
    private fun outbound(encoded: Int) = StatsEntry("outbound-rtp", mapOf("kind" to "video", "framesEncoded" to encoded))
    private fun source(frames: Int) = StatsEntry("media-source", mapOf("kind" to "video", "frames" to frames, "framesPerSecond" to 0.0))

    // iPhone r1: encoded froze at 246 with the track Live, which a stopped camera and a failing encoder both produce.
    @Test
    fun aFrozenEncoderIsToldApartFromAFrozenCamera() {
        val cameraStopped = videoStatsLines(listOf(source(246), outbound(246)), localTrack = "Live")
        val encoderStuck = videoStatsLines(listOf(source(900), outbound(246)), localTrack = "Live")

        assertEquals("source video frames=246 fps=0.0 size=nullxnull track=Live", cameraStopped.single { it.startsWith("source") })
        assertTrue(encoderStuck.single { it.startsWith("source") }.contains("frames=900"))
        assertTrue(encoderStuck.single { it.startsWith("outbound") }.contains("encoded=246"))
    }

    @Test
    fun audioEntriesAndUnnominatedPairsAreLeftOut() {
        val lines = videoStatsLines(
            listOf(
                StatsEntry("media-source", mapOf("kind" to "audio", "frames" to 1)),
                StatsEntry("candidate-pair", mapOf("nominated" to false, "state" to "succeeded")),
            ),
        )
        assertTrue(lines.isEmpty())
    }

    // follow-r1 Redmi: target fell to 300 kbps at 1080p with limit=none; the line must show the QP the scaler judged.
    @Test
    fun outboundQpAndEncodeTimeAreAveragedOverTheIntervalFromAndroidsBigIntegerCounters() {
        fun sample(qpSum: Long, encoded: Long, encodeSeconds: Double) = StatsEntry(
            "outbound-rtp",
            mapOf(
                "kind" to "video",
                "qpSum" to BigInteger.valueOf(qpSum),
                "framesEncoded" to encoded,
                "totalEncodeTime" to encodeSeconds,
                "qualityLimitationDurations" to mapOf("none" to 40.0, "bandwidth" to 0.0),
            ),
        )
        val line = videoStatsLines(listOf(sample(5_500, 200, 3.5)), previous = listOf(sample(1_000, 100, 2.0))).single()

        assertTrue(" qp=45.0 " in line, line)
        assertTrue(" encodeMs=15.0 " in line, line)
        assertTrue("limitDurations={none=40.0, bandwidth=0.0}" in line, line)
    }

    // follow-r1 Redmi: 1080p in, 12 fps decoded and the rest dropped; decodeMs above 33 is a decoder that cannot keep up.
    @Test
    fun inboundDecodeAndJitterBufferTimeArePerFrameOverTheInterval() {
        fun sample(decoded: Long, decodeSeconds: Double, qpSum: Long, jbSeconds: Double, emitted: Long) = StatsEntry(
            "inbound-rtp",
            mapOf(
                "kind" to "video",
                "framesDecoded" to decoded,
                "totalDecodeTime" to decodeSeconds,
                "qpSum" to BigInteger.valueOf(qpSum),
                "jitterBufferDelay" to jbSeconds,
                "jitterBufferEmittedCount" to BigInteger.valueOf(emitted),
                "powerEfficientDecoder" to true,
            ),
        )
        val line = videoStatsLines(
            listOf(sample(160, 18.0, 2_400, 30.0, 160)),
            previous = listOf(sample(100, 10.0, 1_200, 18.0, 100)),
        ).single()

        assertTrue(" qp=20.0 " in line, line)
        assertTrue(" decodeMs=133.3 " in line, line)
        assertTrue(" jbMs=200.0 " in line, line)
        assertTrue(line.endsWith("powerEfficient=true"), line)
    }

    @Test
    fun averagesAreNullWithoutTheCounterOrWithoutNewFrames() {
        val stalled = StatsEntry("outbound-rtp", mapOf("kind" to "video", "framesEncoded" to 246, "qpSum" to 9_000))
        val noQp = StatsEntry("outbound-rtp", mapOf("kind" to "video", "framesEncoded" to 300))

        assertTrue(" qp=null " in videoStatsLines(listOf(stalled), previous = listOf(stalled)).single())
        assertTrue(" qp=null " in videoStatsLines(listOf(noQp)).single())
        assertTrue(" qp=36.6 " in videoStatsLines(listOf(stalled)).single())
    }

    // iPhone retest Redmi: encoded sat at 2778 for minutes while the camera kept going; the delta reads 0 at a glance.
    @Test
    fun encodedDeltaCountsFramesEncodedInTheInterval() {
        val now = StatsEntry("outbound-rtp", mapOf("kind" to "video", "framesEncoded" to BigInteger.valueOf(2_778)))

        assertTrue(" encodedDelta=0 " in videoStatsLines(listOf(now), previous = listOf(now)).single())
        assertTrue(" encodedDelta=37 " in videoStatsLines(listOf(now), previous = listOf(outbound(2_741))).single())
    }

    // Redmi receiving iPhone H.264 at 360p: 584 ms a frame at 30 fps is 17.5 frames inside OMX.qcom.video.decoder.avc.
    @Test
    fun inboundLinesNameTheCodecAndCountFramesInFlight() {
        fun video(decoded: Long, decodeSeconds: Double) = StatsEntry(
            "inbound-rtp",
            mapOf("kind" to "video", "codecId" to "CIT01_102", "framesDecoded" to decoded, "totalDecodeTime" to decodeSeconds, "framesPerSecond" to 30.0),
        )
        val codec = StatsEntry("codec", mapOf("mimeType" to "video/H264"), id = "CIT01_102")
        val line = videoStatsLines(listOf(video(250, 87.6), codec), previous = listOf(video(100, 0.0))).single()

        assertTrue(line.startsWith("inbound video codec=video/H264 "), line)
        assertTrue(" decodeMs=584.0 inFlight=17.5 " in line, line)
    }

    // A/V sync holds audio back to the video's delay: 751-765 ms on the Redmi while it decoded H.264.
    @Test
    fun theInboundAudioLineShowsItsJitterBufferDelayOverTheInterval() {
        fun audio(emitted: Long, jbSeconds: Double, targetSeconds: Double, concealed: Long) = StatsEntry(
            "inbound-rtp",
            mapOf(
                "kind" to "audio", "codecId" to "CIT01_111", "jitterBufferEmittedCount" to BigInteger.valueOf(emitted),
                "jitterBufferDelay" to jbSeconds, "jitterBufferTargetDelay" to targetSeconds, "concealedSamples" to concealed,
            ),
        )
        val codec = StatsEntry("codec", mapOf("mimeType" to "audio/opus"), id = "CIT01_111")
        val line = videoStatsLines(listOf(audio(480_000, 280_000.0, 230_000.0, 900), codec), previous = listOf(audio(240_000, 100_000.0, 50_000.0, 100))).single()

        assertEquals("inbound audio codec=audio/opus jbMs=750.0 jbTargetMs=750.0 concealed=800", line)
    }
}
