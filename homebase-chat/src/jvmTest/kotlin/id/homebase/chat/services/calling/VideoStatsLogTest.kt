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
}
