package id.homebase.api.video

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val BUDGET = 1_000L

/** A structurally valid GIF (no pixel data worth decoding), padded with comment blocks to about [size] bytes. */
private fun gif(width: Int = 800, height: Int = 450, frames: Int = 30, delayCs: Int = 4, size: Int = 0): ByteArray {
    val b = ArrayList<Byte>()
    fun u8(v: Int) { b += v.toByte() }
    fun u16(v: Int) { u8(v and 0xFF); u8(v shr 8) }
    "GIF89a".forEach { u8(it.code) }
    u16(width); u16(height); u8(0); u8(0); u8(0)
    repeat(frames) {
        u8(0x21); u8(0xF9); u8(4); u8(0); u16(delayCs); u8(0); u8(0)
        u8(0x2C); u16(0); u16(0); u16(width); u16(height); u8(0)
        u8(2); u8(1); u8(0); u8(0)
    }
    while (b.size + 5 < size) {
        val n = minOf(255, size - b.size - 5)
        u8(0x21); u8(0xFE); u8(n); repeat(n) { u8(0x20) }; u8(0)
    }
    u8(0x3B)
    return b.toByteArray()
}

private class FakeFfmpeg(vararg results: ByteArray?) {
    private val results = results.toMutableList()
    val calls = mutableListOf<List<String>>()
    val graphs get() = calls.map { it[it.indexOf("-filter_complex") + 1] }

    suspend fun transcode(input: ByteArray, extension: String, args: List<String>): ByteArray? {
        calls += args
        return results.removeAt(0)
    }
}

class GifShrinkerTest {
    private val input = gif(size = 5_000)

    private suspend fun shrink(bytes: ByteArray, ffmpeg: FakeFfmpeg) = GifShrinker.shrink(bytes, BUDGET, ffmpeg::transcode)

    /** Every step over budget, so the whole ladder runs. */
    private fun overBudget() = FakeFfmpeg(gif(size = 3_000), gif(size = 3_000), gif(size = 3_000))

    @Test
    fun gifWithinBudget_isUntouched() = runTest {
        val small = gif(size = 900)
        val ffmpeg = FakeFfmpeg()
        assertSame(small, shrink(small, ffmpeg))
        assertTrue(ffmpeg.calls.isEmpty())
    }

    @Test
    fun nonGif_isUntouched() = runTest {
        val png = ByteArray(5_000).also { byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47).copyInto(it) }
        val ffmpeg = FakeFfmpeg()
        assertSame(png, shrink(png, ffmpeg))
        assertTrue(ffmpeg.calls.isEmpty())
    }

    @Test
    fun firstStepWithinBudget_stopsTheLadder() = runTest {
        val fits = gif(size = 600)
        val ffmpeg = FakeFfmpeg(fits)
        assertSame(fits, shrink(input, ffmpeg))
        assertEquals(1, ffmpeg.calls.size)
        assertTrue("scale=512:288:flags=lanczos" in ffmpeg.graphs[0], ffmpeg.graphs[0])
        assertTrue("max_colors=128" in ffmpeg.graphs[0], ffmpeg.graphs[0])
    }

    @Test
    fun fallsThroughToSmallerSteps() = runTest {
        val fits = gif(size = 800)
        val ffmpeg = FakeFfmpeg(gif(size = 3_000), gif(size = 2_000), fits)
        assertSame(fits, shrink(input, ffmpeg))
        assertEquals(3, ffmpeg.calls.size)
        assertTrue("scale=384:216:" in ffmpeg.graphs[1] && "max_colors=128" in ffmpeg.graphs[1], ffmpeg.graphs[1])
        assertTrue("scale=384:216:" in ffmpeg.graphs[2] && "max_colors=64" in ffmpeg.graphs[2], ffmpeg.graphs[2])
    }

    @Test
    fun nothingFits_keepsTheSmallestResult() = runTest {
        val smallest = gif(size = 1_500)
        val ffmpeg = FakeFfmpeg(gif(size = 3_000), smallest, gif(size = 2_500))
        assertSame(smallest, shrink(input, ffmpeg))
    }

    @Test
    fun noStepBeatsTheOriginal_keepsTheOriginal() = runTest {
        val ffmpeg = FakeFfmpeg(gif(size = 6_000), gif(size = 7_000), gif(size = 8_000))
        assertSame(input, shrink(input, ffmpeg))
        assertEquals(3, ffmpeg.calls.size)
    }

    @Test
    fun ffmpegFailure_keepsTheOriginal() = runTest {
        val failed = FakeFfmpeg(null, gif(size = 600))
        assertSame(input, shrink(input, failed))
        assertEquals(1, failed.calls.size)

        assertSame(input, GifShrinker.shrink(input, BUDGET) { _, _, _ -> error("ffmpeg crashed") })
    }

    @Test
    fun stillOutputOfAnAnimation_keepsTheOriginal() = runTest {
        assertSame(input, shrink(input, FakeFfmpeg(gif(frames = 1, size = 600))))
    }

    @Test
    fun timeout_keepsTheOriginal() = runTest {
        assertSame(input, GifShrinker.shrink(input, BUDGET) { _, _, _ -> awaitCancellation() })
    }

    @Test
    fun inputOverTheByteCap_isUntouched() = runTest {
        val huge = ByteArray(GifShrinker.MAX_INPUT_BYTES + 1).also { gif().copyInto(it) }
        val ffmpeg = FakeFfmpeg()
        assertSame(huge, shrink(huge, ffmpeg))
        assertTrue(ffmpeg.calls.isEmpty())
    }

    @Test
    fun inputOverThePixelCap_isUntouched() = runTest {
        // 1024² scales to 512² for the first pass: 128 frames is exactly the cap, 129 is over.
        val atCap = FakeFfmpeg(gif(size = 600))
        shrink(gif(width = 1024, height = 1024, frames = 128, size = 5_000), atCap)
        assertEquals(1, atCap.calls.size)

        val overCap = FakeFfmpeg()
        val big = gif(width = 1024, height = 1024, frames = 129, size = 5_000)
        assertSame(big, shrink(big, overCap))
        assertTrue(overCap.calls.isEmpty())
    }

    @Test
    fun frameRateIsOnlyEverLowered() = runTest {
        val fast = overBudget() // 4 cs per frame = 25 fps
        shrink(input, fast)
        assertTrue(fast.graphs.take(2).none { "fps=" in it }, fast.graphs.toString())
        assertTrue(fast.graphs[2].startsWith("fps=12,"), fast.graphs[2])

        for (delayCs in listOf(10, 0)) { // 10 fps, and ffmpeg plays a 0 cs delay at 10 cs
            val slow = overBudget()
            shrink(gif(delayCs = delayCs, size = 5_000), slow)
            assertTrue(slow.graphs.none { "fps=" in it }, "delay=$delayCs ${slow.graphs}")
        }
    }

    @Test
    fun portraitSource_scalesTheLongSide() = runTest {
        val ffmpeg = overBudget()
        shrink(gif(width = 450, height = 800, size = 5_000), ffmpeg)
        assertTrue("scale=288:512:" in ffmpeg.graphs[0], ffmpeg.graphs[0])
        assertTrue("scale=216:384:" in ffmpeg.graphs[1], ffmpeg.graphs[1])
    }

    @Test
    fun smallSource_isNeverUpscaledAndDuplicateStepsAreSkipped() = runTest {
        val ffmpeg = overBudget()
        shrink(gif(width = 300, height = 200, size = 5_000), ffmpeg)
        assertEquals(2, ffmpeg.calls.size, ffmpeg.graphs.toString())
        assertTrue(ffmpeg.graphs.all { "scale=300:200:" in it }, ffmpeg.graphs.toString())
    }
}
