package id.homebase.agent

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SegmenterTest {
    private class Part(val ms: Int, val tone: Double = 0.0, val noise: Double = 0.0)

    private fun signal(vararg parts: Part, rate: Int = CALL_SAMPLE_RATE, channels: Int = 1): ShortArray {
        val random = Random(7)
        var phase = 0
        val out = ArrayList<Short>()
        for (part in parts) {
            for (i in 0 until rate * part.ms / 1000) {
                val t = sin(2 * PI * 440.0 * phase++ / rate) * part.tone * sqrt(2.0)
                val n = (random.nextDouble() * 2 - 1) * part.noise * sqrt(3.0)
                val v = ((t + n) * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
                repeat(channels) { out += v }
            }
        }
        return out.toShortArray()
    }

    private fun run(pcm: ShortArray, segmenter: Segmenter = Segmenter(), chunk: Int = 480): List<SegmentEvent> {
        val events = ArrayList<SegmentEvent>()
        var i = 0
        while (i < pcm.size) {
            events += segmenter.feed(pcm.copyOfRange(i, minOf(pcm.size, i + chunk)))
            i += chunk
        }
        return events
    }

    private fun List<SegmentEvent>.utterances() = filterIsInstance<SegmentEvent.Done>().map { it.utterance }

    private fun near(expected: Long, actual: Long, slack: Long = 60) = assertTrue(abs(expected - actual) <= slack, "expected about $expected but was $actual")

    @Test
    fun toneBurstsSeparatedBySilenceGiveTheRightCountAndBoundaries() {
        val events = run(signal(Part(500), Part(1_000, 0.3), Part(1_000), Part(600, 0.3), Part(1_000)))
        val found = events.utterances()
        assertEquals(2, found.size)
        near(500, found[0].startMs); near(1_500, found[0].endMs)
        near(2_500, found[1].startMs); near(3_100, found[1].endMs)
        assertEquals((found[0].endMs - found[0].startMs) * SEGMENT_RATE / 1000, found[0].pcm16k.size.toLong())
        assertEquals(2, events.filterIsInstance<SegmentEvent.Onset>().size)
    }

    @Test
    fun onsetIsReportedOnceThreeVoicedFramesHaveBeenSeen() {
        val events = run(signal(Part(500), Part(1_000, 0.3), Part(1_000)))
        val onset = events.filterIsInstance<SegmentEvent.Onset>().single()
        near(500, onset.atMs)
        assertTrue(events.indexOfFirst { it is SegmentEvent.Onset } < events.indexOfFirst { it is SegmentEvent.Done })
    }

    @Test
    fun shortBlipsAreDropped() {
        val events = run(signal(Part(500), Part(200, 0.3), Part(1_000), Part(300, 0.3), Part(1_000)))
        assertTrue(events.utterances().isEmpty())
    }

    @Test
    fun aPauseShorterThanSevenHundredMillisecondsDoesNotSplit() {
        val found = run(signal(Part(500), Part(500, 0.3), Part(500), Part(500, 0.3), Part(1_000))).utterances()
        assertEquals(1, found.size)
        near(1_500, found[0].durationMs)
    }

    @Test
    fun aTwentyFiveSecondToneIsForceCutAtTwentySeconds() {
        val found = run(signal(Part(500), Part(25_000, 0.3), Part(1_000))).utterances()
        assertEquals(2, found.size)
        assertEquals(20_000L, found[0].durationMs)
        assertEquals(found[0].endMs, found[1].startMs)
        near(5_000, found[1].durationMs)
    }

    @Test
    fun theNoiseFloorAdaptsWhenBackgroundNoiseDropsAway() {
        val loud = run(signal(Part(1_000, 0.0, 0.03), Part(1_000, 0.05, 0.03), Part(500, 0.0, 0.03)))
        assertTrue(loud.utterances().isEmpty(), "speech under a loud floor is not heard")

        val segmenter = Segmenter()
        val quiet = run(signal(Part(1_000, 0.0, 0.03), Part(3_000, 0.0, 0.003), Part(1_000, 0.05, 0.03), Part(1_000, 0.0, 0.003)), segmenter)
        assertEquals(1, quiet.utterances().size)
        near(4_000, quiet.utterances().single().startMs, 100)
    }

    @Test
    fun steadyBackgroundNoiseIsNotSpeech() {
        assertTrue(run(signal(Part(10_000, 0.0, 0.02))).isEmpty())
    }

    @Test
    fun stereoIsDownmixedAndOtherRatesAreAccepted() {
        val stereo = run(signal(Part(500), Part(1_000, 0.3), Part(1_000), channels = 2), Segmenter(CALL_SAMPLE_RATE, 2), 960)
        assertEquals(1, stereo.utterances().size)
        val narrow = run(signal(Part(500), Part(1_000, 0.3), Part(1_000), rate = 16_000), Segmenter(16_000), 160)
        assertEquals(1, narrow.utterances().size)
        near(1_000, narrow.utterances().single().durationMs)
    }

    @Test
    fun oddChunkSizesGiveTheSameBoundaries() {
        val pcm = signal(Part(500), Part(1_000, 0.3), Part(1_000))
        val a = run(pcm, chunk = 480).utterances().single()
        val b = run(pcm, Segmenter(), chunk = 777).utterances().single()
        assertEquals(a.startMs, b.startMs)
        assertEquals(a.endMs, b.endMs)
    }

    @Test
    fun wavBytesCarriesAValidHeader() {
        val wav = wavBytes(ShortArray(160) { 1 })
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(44 + 320, wav.size)
    }
}
