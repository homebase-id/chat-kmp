package id.homebase.api.image.gif

import id.homebase.api.lib.image.ImageFormatDetector
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** Every frame of [gif], each its own copy of the canvas. */
internal fun decode(gif: ByteArray): List<IntArray> {
    val decoder = assertNotNull(GifDecoder.open(gif))
    val frames = decoder.frames()
    return List(decoder.frameCount) { frames.next().copyOf() }
}

class GifTest {
    private val red = 0xFFE01010.toInt()
    private val green = 0xFF10C020.toInt()
    private val blue = 0xFF2030E0.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun frame(w: Int, h: Int, paint: (x: Int, y: Int) -> Int) = IntArray(w * h) { paint(it % w, it / w) }


    @Test
    fun framesOfPaletteColoursComeBackExactly() {
        val w = 37
        val h = 23
        val frames = listOf(
            frame(w, h) { x, y -> if ((x / 4 + y / 4) % 2 == 0) red else white },
            // Only a corner changes: stored as that rectangle, the rest transparent over the last frame.
            frame(w, h) { x, y -> if (x > 30 && y > 15) blue else if ((x / 4 + y / 4) % 2 == 0) red else white },
            frame(w, h) { _, _ -> green },
        )
        val writer = GifWriter(w, h, intArrayOf(red, green, blue, white), loopCount = 0, anyTransparency = false)
        frames.forEach { writer.add(writer.indices(it), 7) }
        val gif = writer.finish()

        val decoded = decode(gif)
        assertEquals(3, decoded.size)
        frames.zip(decoded).forEach { (sent, read) -> assertContentEquals(sent, read) }
        assertEquals(21, ImageFormatDetector.parseGif(gif)?.durationCs)
    }

    @Test
    fun aTransparentFrameAfterAnOpaqueOneDoesNotShowItThrough() = runTest {
        val w = 16
        val h = 16
        val frames = listOf(
            frame(w, h) { _, _ -> red },
            frame(w, h) { x, _ -> if (x >= 8) blue else 0 },
            frame(w, h) { _, _ -> red },
        )
        val writer = GifWriter(w, h, intArrayOf(red, blue), loopCount = null, anyTransparency = true)
        frames.forEach { writer.add(writer.indices(it), 10) }
        frames.zip(decode(writer.finish())).forEach { (sent, read) -> assertContentEquals(sent, read) }
        // And through the whole re-encode, which finds the transparency itself.
        val source = GifWriter(w, h, intArrayOf(red, blue), loopCount = null, anyTransparency = true)
            .apply { frames.forEach { add(indices(it), 10) } }.finish()
        frames.zip(decode(assertNotNull(reencodeGif(source, w, h, 8)))).forEach { (sent, read) -> assertContentEquals(sent, read) }
    }

    @Test
    fun aTruncatedOrOversizedGifDecodesWhatItHasWithoutThrowing() {
        val w = 32
        val h = 32
        val writer = GifWriter(w, h, intArrayOf(red, blue), loopCount = 0, anyTransparency = false)
        writer.add(writer.indices(frame(w, h) { _, _ -> red }), 10)
        writer.add(writer.indices(frame(w, h) { x, y -> if ((x + y) % 2 == 0) blue else red }), 10)
        val gif = writer.finish()
        for (cut in 20 until gif.size) {
            val frames = GifDecoder.open(gif.copyOf(cut))?.let { d -> val f = d.frames(); List(d.frameCount) { f.next().copyOf() } }
            // The first frame is solid red: whatever survives of it is red, never colour 0 painted over the rest.
            frames?.firstOrNull()?.forEach { assertTrue(it == red || it == 0, "cut at $cut") }
        }
        // A frame descriptor claiming more pixels than the screen ends the walk instead of sizing a buffer from it.
        val huge = gif.copyOf().also { g ->
            val at = (0 until g.size).first { g[it] == 0x2C.toByte() }
            g[at + 5] = 0xFF.toByte(); g[at + 6] = 0xFF.toByte(); g[at + 7] = 0xFF.toByte(); g[at + 8] = 0xFF.toByte()
        }
        assertEquals(null, GifDecoder.open(huge))
    }

    @Test
    fun aHeldFrameIsWrittenOnceWhenTheFrameRateIsLowered() = runTest {
        val w = 8
        val h = 8
        val writer = GifWriter(w, h, intArrayOf(red, blue, green), loopCount = 0, anyTransparency = false)
        repeat(10) { f -> writer.add(writer.indices(frame(w, h) { _, _ -> if (f % 2 == 0) red else blue }), 2) }
        writer.add(writer.indices(frame(w, h) { _, _ -> green }), 100)
        val out = assertNotNull(reencodeGif(writer.finish(), w, h, 8, fps = 5))
        val info = assertNotNull(ImageFormatDetector.parseGif(out))
        assertEquals(120, info.durationCs)
        // At 5 fps, 0.2 s of 50 fps flicker is one tick, and the one-second hold one frame, not five.
        assertEquals(2, info.frameCount)
    }

    @Test
    fun transparentPixelsAreClearedBetweenFrames() {
        val w = 16
        val h = 16
        val frames = listOf(
            frame(w, h) { x, _ -> if (x < 8) red else 0 },
            frame(w, h) { x, _ -> if (x >= 8) blue else 0 },
        )
        val writer = GifWriter(w, h, intArrayOf(red, blue), loopCount = null, anyTransparency = true)
        frames.forEach { writer.add(writer.indices(it), 10) }
        frames.zip(decode(writer.finish())).forEach { (sent, read) -> assertContentEquals(sent, read) }
    }

    @Test
    fun aLongAnimationRoundTripsPastTheLzwTableLimit() {
        // Noise fills the 4096-entry table, so the encoder clears it mid-frame and the decoder must follow.
        val w = 200
        val h = 150
        var seed = 7
        val palette = IntArray(16) { (0xFF shl 24) or (it * 0x101010) }
        val frames = List(3) { IntArray(w * h) { seed = seed * 1103515245 + 12345; palette[(seed ushr 16) and 15] } }
        val writer = GifWriter(w, h, palette, loopCount = 0, anyTransparency = false)
        frames.forEach { writer.add(writer.indices(it), 5) }
        frames.zip(decode(writer.finish())).forEach { (sent, read) -> assertContentEquals(sent, read) }
    }

    @Test
    fun aReencodeHasTheAskedSizeColoursAndFrames() = runTest {
        val w = 400
        val h = 240
        // 25 frames at 4 cs (25 fps), a moving gradient: many colours.
        val writer = GifWriter(w, h, IntArray(255) { (0xFF shl 24) or (it shl 16) or ((255 - it) shl 8) or (it / 2) }, loopCount = 0, anyTransparency = false)
        repeat(25) { f -> writer.add(writer.indices(frame(w, h) { x, y -> (0xFF shl 24) or (((x + f * 8) % 255) shl 16) or (((255 - (x + f * 8) % 255)) shl 8) or (((x + f * 8) % 255) / 2) }), 4) }
        val source = writer.finish()

        val out = assertNotNull(reencodeGif(source, 200, 120, colors = 64, fps = 12))
        val info = assertNotNull(ImageFormatDetector.parseGif(out))
        assertEquals(200 to 120, info.width to info.height)
        assertEquals(12, info.frameCount, "1 s at 12 fps")
        assertEquals(100, info.durationCs)
        val colours = decode(out).flatMap { it.asList() }.toSet()
        assertTrue(colours.size <= 63, "${colours.size} colours")
    }

    @Test
    fun anythingButAGifIsNotDecoded() = runTest {
        assertEquals(null, GifDecoder.open(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) + ByteArray(20)))
        assertEquals(null, reencodeGif(ByteArray(30), 10, 10, 16))
    }
}
