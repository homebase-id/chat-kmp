package id.homebase.api.video

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers [TieredVideoDecoder]'s orchestration rules so they aren't reliant on four separate
 * platform tests to each catch a regression. Real decoders are exercised in per-platform tests.
 */
class TieredVideoDecoderTest {

    @Test
    fun poster_returnsPrimary_whenPrimarySucceeds() = runTest {
        val primary = FakeDecoder(posterBytes = byteArrayOf(1, 2, 3))
        val fallback = FakeDecoder(posterBytes = byteArrayOf(9, 9, 9))
        val tiered = TieredVideoDecoder(primary, fallback)

        val out = tiered.extractPosterFrame("any")

        assertEquals(listOf<Byte>(1, 2, 3), out?.toList())
        assertEquals(0, fallback.posterCalls, "fallback must not run when primary returns bytes")
    }

    @Test
    fun poster_fallsBackToSecondary_whenPrimaryReturnsNull() = runTest {
        val primary = FakeDecoder(posterBytes = null)
        val fallback = FakeDecoder(posterBytes = byteArrayOf(7, 7))
        val tiered = TieredVideoDecoder(primary, fallback)

        val out = tiered.extractPosterFrame("any")

        assertEquals(listOf<Byte>(7, 7), out?.toList())
        assertEquals(1, fallback.posterCalls)
    }

    @Test
    fun poster_fallsBackToSecondary_whenPrimaryThrows() = runTest {
        val primary = FakeDecoder(posterThrows = RuntimeException("codec refused"))
        val fallback = FakeDecoder(posterBytes = byteArrayOf(7))
        val tiered = TieredVideoDecoder(primary, fallback)

        val out = tiered.extractPosterFrame("any")

        assertEquals(listOf<Byte>(7), out?.toList())
    }

    @Test
    fun poster_returnsNull_whenBothFailAndNoFallback() = runTest {
        val tiered = TieredVideoDecoder(FakeDecoder(posterBytes = null), fallback = null)
        assertNull(tiered.extractPosterFrame("any"))
    }

    @Test
    fun strip_skipsFallback_whenPrimaryEmitsAll() = runTest {
        val primary = FakeDecoder(stripFrames = (0..2).map { frame(it) })
        val fallback = FakeDecoder(stripFrames = (0..2).map { frame(it, jpeg = byteArrayOf(99)) })
        val tiered = TieredVideoDecoder(primary, fallback)

        val frames = tiered.extractThumbnailStrip("any", 3000, 3, 96).toList()

        assertEquals(listOf(0, 1, 2), frames.map { it.index })
        assertTrue(frames.none { it.jpegBytes.contentEquals(byteArrayOf(99)) })
        assertEquals(0, fallback.stripCalls, "fallback must not run when primary covered all indices")
    }

    @Test
    fun strip_fillsOnlyMissingIndices_fromFallback() = runTest {
        // Primary emits 0 and 2; fallback emits the full strip but only index 1 should land.
        val primary = FakeDecoder(stripFrames = listOf(frame(0), frame(2)))
        val fallback = FakeDecoder(
            stripFrames = (0..2).map { frame(it, jpeg = byteArrayOf(99.toByte())) },
        )
        val tiered = TieredVideoDecoder(primary, fallback)

        val frames = tiered.extractThumbnailStrip("any", 3000, 3, 96).toList()

        assertEquals(listOf(0, 2, 1), frames.map { it.index }, "primary-first emission order, then fallback fills 1")
        val fromFallback = frames.filter { it.jpegBytes.contentEquals(byteArrayOf(99.toByte())) }
        assertEquals(listOf(1), fromFallback.map { it.index }, "only the missing index should come from fallback")
    }

    @Test
    fun strip_usesFallbackEntirely_whenPrimaryEmitsZero() = runTest {
        val primary = FakeDecoder(stripFrames = emptyList())
        val fallback = FakeDecoder(stripFrames = (0..2).map { frame(it) })
        val tiered = TieredVideoDecoder(primary, fallback)

        val frames = tiered.extractThumbnailStrip("any", 3000, 3, 96).toList()

        assertEquals(listOf(0, 1, 2), frames.map { it.index })
        assertEquals(1, fallback.stripCalls)
    }

    @Test
    fun strip_skipsFallback_whenPrimaryThrowsButCoveredAllFirst() = runTest {
        // Primary emits all indices then throws — the runner should treat the strip as full.
        val primary = FakeDecoder(
            stripFlow = flow {
                (0..2).forEach { emit(frame(it)) }
                throw RuntimeException("late error after full emission")
            },
        )
        val fallback = FakeDecoder(stripFrames = (0..2).map { frame(it, jpeg = byteArrayOf(99)) })
        val tiered = TieredVideoDecoder(primary, fallback)

        val frames = tiered.extractThumbnailStrip("any", 3000, 3, 96).toList()

        assertEquals(listOf(0, 1, 2), frames.map { it.index })
        assertEquals(0, fallback.stripCalls)
    }

    @Test
    fun strip_emptyFrameCount_isNoOp() = runTest {
        val primary = FakeDecoder(stripFrames = (0..2).map { frame(it) })
        val fallback = FakeDecoder(stripFrames = (0..2).map { frame(it) })
        val tiered = TieredVideoDecoder(primary, fallback)

        assertTrue(tiered.extractThumbnailStrip("any", 3000, 0, 96).toList().isEmpty())
        assertTrue(tiered.extractThumbnailStrip("any", 0, 3, 96).toList().isEmpty())
        assertEquals(0, primary.stripCalls)
        assertEquals(0, fallback.stripCalls)
    }
}

private fun frame(index: Int, jpeg: ByteArray = byteArrayOf(0)): IndexedFrame =
    IndexedFrame(index = index, timeMs = (index * 1000).toLong(), jpegBytes = jpeg)

private class FakeDecoder(
    private val posterBytes: ByteArray? = null,
    private val posterThrows: Throwable? = null,
    private val stripFrames: List<IndexedFrame> = emptyList(),
    private val stripFlow: Flow<IndexedFrame>? = null,
) : VideoDecoder {
    var posterCalls = 0
    var stripCalls = 0

    override suspend fun extractPosterFrame(videoPath: String): ByteArray? {
        posterCalls++
        posterThrows?.let { throw it }
        return posterBytes
    }

    override fun extractThumbnailStrip(
        videoPath: String,
        durationMs: Long,
        frameCount: Int,
        targetHeightPx: Int,
    ): Flow<IndexedFrame> {
        stripCalls++
        if (stripFlow != null) return stripFlow
        return if (stripFrames.isEmpty()) emptyFlow() else flow { stripFrames.forEach { emit(it) } }
    }
}
