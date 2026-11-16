package id.homebase.api.video

import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.platformLogWriter
import id.homebase.api.video.GifShrinker.Encoding
import id.homebase.api.video.GifShrinker.OverBudget
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val BUDGET = 2_000L

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

private class FakeEncoder(vararg results: ByteArray?) {
    private val results = results.toMutableList()
    val calls = mutableListOf<Encoding>()

    suspend fun encode(input: ByteArray, encoding: Encoding): ByteArray? {
        calls += encoding
        return results.removeAt(0)
    }
}

class GifShrinkerTest {
    private val input = gif(size = 5_000)

    private suspend fun shrink(bytes: ByteArray, encoder: FakeEncoder) = GifShrinker.shrink(bytes, BUDGET, encoder::encode)

    /** Every step over budget, so the whole ladder runs. */
    private fun overBudget() = FakeEncoder(gif(size = 3_000), gif(size = 3_000), gif(size = 3_000))

    @Test
    fun gifWithinBudget_isUntouched() = runTest {
        val small = gif(size = 900)
        val encoder = FakeEncoder()
        val result = shrink(small, encoder)
        assertSame(small, result.bytes)
        assertNull(result.overBudget)
        assertTrue(encoder.calls.isEmpty())
    }

    @Test
    fun nonGif_isUntouched() = runTest {
        val png = ByteArray(5_000).also { byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47).copyInto(it) }
        val encoder = FakeEncoder()
        val result = shrink(png, encoder)
        assertSame(png, result.bytes)
        assertNull(result.overBudget)
        assertTrue(encoder.calls.isEmpty())
    }

    @Test
    fun firstStepWithinBudget_stopsTheLadder() = runTest {
        val fits = gif(size = 600)
        val encoder = FakeEncoder(fits)
        val result = shrink(input, encoder)
        assertSame(fits, result.bytes)
        assertNull(result.overBudget)
        assertEquals(listOf(Encoding(512, 288, 128, null)), encoder.calls)
    }

    @Test
    fun fallsThroughToSmallerSteps() = runTest {
        val fits = gif(size = 800)
        val encoder = FakeEncoder(gif(size = 3_000), gif(size = 2_500), fits)
        assertSame(fits, shrink(input, encoder).bytes)
        assertEquals(listOf(Encoding(512, 288, 128, null), Encoding(384, 216, 128, null), Encoding(384, 216, 64, 12)), encoder.calls)
    }

    @Test
    fun startStep_skipsStepsThatCannotFit() {
        assertEquals(0, GifShrinker.startStep(3_500, 1_000))
        assertEquals(1, GifShrinker.startStep(3_501, 1_000))
        assertEquals(1, GifShrinker.startStep(7_000, 1_000))
        assertEquals(2, GifShrinker.startStep(7_001, 1_000))
    }

    @Test
    fun startStep_matchesTheMeasuredDeviceCases() {
        val trayBudget = 2L * 1024 * 1024
        assertEquals(2, GifShrinker.startStep(35 * 1024 * 1024, trayBudget)) // only step 3 fit: 1.95 MB
        assertEquals(0, GifShrinker.startStep(2_730_000, trayBudget)) // step 1 fit: 1.23 MB
    }

    @Test
    fun farOverBudget_startsLowerDownTheLadder() = runTest {
        val fiveTimes = FakeEncoder(gif(size = 3_000), gif(size = 3_000))
        GifShrinker.shrink(input, 1_000, fiveTimes::encode)
        assertEquals(listOf(Encoding(384, 216, 128, null), Encoding(384, 216, 64, 12)), fiveTimes.calls)

        val tenTimes = FakeEncoder(gif(size = 3_000))
        GifShrinker.shrink(input, 500, tenTimes::encode)
        assertEquals(listOf(Encoding(384, 216, 64, 12)), tenTimes.calls)
    }

    @Test
    fun nothingFits_keepsTheSmallestResult() = runTest {
        val smallest = gif(size = 2_200)
        val encoder = FakeEncoder(gif(size = 3_000), smallest, gif(size = 2_500))
        val result = shrink(input, encoder)
        assertSame(smallest, result.bytes)
        assertEquals(OverBudget.LadderExhausted, result.overBudget)
    }

    @Test
    fun noStepBeatsTheOriginal_keepsTheOriginal() = runTest {
        val encoder = FakeEncoder(gif(size = 6_000), gif(size = 7_000), gif(size = 8_000))
        val result = shrink(input, encoder)
        assertSame(input, result.bytes)
        assertEquals(OverBudget.LadderExhausted, result.overBudget)
        assertEquals(3, encoder.calls.size)
    }

    @Test
    fun encodeFailure_keepsTheOriginal() = runTest {
        val failed = FakeEncoder(null, gif(size = 600))
        val result = shrink(input, failed)
        assertSame(input, result.bytes)
        assertEquals(OverBudget.EncodeFailed, result.overBudget)
        assertEquals(1, failed.calls.size)

        val threw = GifShrinker.shrink(input, BUDGET) { _, _ -> error("encoder crashed") }
        assertSame(input, threw.bytes)
        assertEquals(OverBudget.EncodeFailed, threw.overBudget)
    }

    @Test
    fun stillOutputOfAnAnimation_keepsTheOriginal() = runTest {
        val result = shrink(input, FakeEncoder(gif(frames = 1, size = 600)))
        assertSame(input, result.bytes)
        assertEquals(OverBudget.FrameLoss, result.overBudget)
    }

    @Test
    fun timeout_keepsTheOriginal() = runTest {
        val result = GifShrinker.shrink(input, BUDGET) { _, _ -> awaitCancellation() }
        assertSame(input, result.bytes)
        assertEquals(OverBudget.Timeout, result.overBudget)
    }

    @Test
    fun inputOverTheByteCap_isUntouched() = runTest {
        val huge = ByteArray(GifShrinker.MAX_INPUT_BYTES + 1).also { gif().copyInto(it) }
        val encoder = FakeEncoder()
        val result = shrink(huge, encoder)
        assertSame(huge, result.bytes)
        assertEquals(OverBudget.InputTooLarge, result.overBudget)
        assertTrue(encoder.calls.isEmpty())
    }

    @Test
    fun overThePixelCapAtEveryStep_isUntouched() = runTest {
        // At 10 fps no step drops frames, so 384² is the smallest frame: 227 frames fit the cap, 228 don't.
        val atCap = FakeEncoder(gif(size = 600))
        shrink(gif(width = 1024, height = 1024, frames = 227, delayCs = 10, size = 5_000), atCap)
        assertEquals(1, atCap.calls.size)

        val overCap = FakeEncoder()
        val big = gif(width = 1024, height = 1024, frames = 228, delayCs = 10, size = 5_000)
        val result = shrink(big, overCap)
        assertSame(big, result.bytes)
        assertEquals(OverBudget.TooManyPixels, result.overBudget)
        assertTrue(overCap.calls.isEmpty())
    }

    @Test
    fun overThePixelCapAtTheFirstStep_startsAtTheFirstStepUnderIt() = runTest {
        // 1024² scales to 512² for step 1: 128 frames is exactly the cap, 129 is over it but fits at 384².
        val atCap = FakeEncoder(gif(size = 600))
        shrink(gif(width = 1024, height = 1024, frames = 128, size = 5_000), atCap)
        assertEquals(listOf(Encoding(512, 512, 128, null)), atCap.calls)

        val overCap = FakeEncoder(gif(size = 600))
        shrink(gif(width = 1024, height = 1024, frames = 129, size = 5_000), overCap)
        assertEquals(listOf(Encoding(384, 384, 128, null)), overCap.calls)
    }

    @Test
    fun longGifStartingAtTheFpsStep_isCappedAtThatStepsFramesAndSize() = runTest {
        // 600 frames at 25 fps: 512x288 buffers 88M pixels, but step 3 keeps 288 frames of 384x216 = 24M.
        val long = gif(frames = 600, size = 5_000)
        val encoder = FakeEncoder(gif(frames = 2, size = 300))
        val result = GifShrinker.shrink(long, 500, encoder::encode)
        assertNull(result.overBudget)
        assertEquals(listOf(Encoding(384, 216, 64, 12)), encoder.calls)
    }

    @Test
    fun overBudget_logsOneWarningWithTheReasonAndNumbers() = runTest {
        val warnings = mutableListOf<String>()
        Logger.setLogWriters(
            listOf(
                object : LogWriter() {
                    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                        if (severity >= Severity.Warn) warnings += message
                    }
                }
            )
        )
        try {
            shrink(input, overBudget())
        } finally {
            Logger.setLogWriters(listOf(platformLogWriter()))
        }
        val line = warnings.single()
        assertTrue("LadderExhausted" in line, line)
        assertTrue("800x450 30f 25 fps 5000 B -> 3000 B" in line, line)
        assertTrue("start=1 steps=3" in line, line)
    }

    @Test
    fun frameRateIsOnlyEverLowered() = runTest {
        val fast = overBudget() // 4 cs per frame = 25 fps
        shrink(input, fast)
        assertEquals(listOf(null, null, 12), fast.calls.map { it.fps })

        for (delayCs in listOf(10, 0)) { // 10 fps, and players show a 0 cs delay at 10 cs
            val slow = overBudget()
            shrink(gif(delayCs = delayCs, size = 5_000), slow)
            assertTrue(slow.calls.all { it.fps == null }, "delay=$delayCs ${slow.calls}")
        }
    }

    @Test
    fun portraitSource_scalesTheLongSide() = runTest {
        val encoder = overBudget()
        shrink(gif(width = 450, height = 800, size = 5_000), encoder)
        assertEquals(listOf(288 to 512, 216 to 384, 216 to 384), encoder.calls.map { it.width to it.height })
    }

    @Test
    fun smallSource_isNeverUpscaledAndDuplicateStepsAreSkipped() = runTest {
        val encoder = overBudget()
        shrink(gif(width = 300, height = 200, size = 5_000), encoder)
        assertEquals(listOf(Encoding(300, 200, 128, null), Encoding(300, 200, 64, 12)), encoder.calls)
    }
}
