package id.homebase.chat.services.calling

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
}
