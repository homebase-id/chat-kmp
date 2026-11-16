package id.homebase.api.image.gif

import id.homebase.api.lib.image.ImageFormatDetector
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        val writer = GifWriter(w, h, intArrayOf(red, green, blue, white), loopCount = 0)
        frames.forEach { writer.add(writer.indices(it), 7) }
        val gif = writer.finish()

        val decoded = decode(gif)
        assertEquals(3, decoded.size)
        frames.zip(decoded).forEach { (sent, read) -> assertContentEquals(sent, read) }
        assertEquals(21, ImageFormatDetector.parseGif(gif)?.durationCs)
    }

    @Test
    fun transparentPixelsAreClearedBetweenFrames() {
        val w = 16
        val h = 16
        val frames = listOf(
            frame(w, h) { x, _ -> if (x < 8) red else 0 },
            frame(w, h) { x, _ -> if (x >= 8) blue else 0 },
        )
        val writer = GifWriter(w, h, intArrayOf(red, blue), loopCount = null)
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
        val writer = GifWriter(w, h, palette, loopCount = 0)
        frames.forEach { writer.add(writer.indices(it), 5) }
        frames.zip(decode(writer.finish())).forEach { (sent, read) -> assertContentEquals(sent, read) }
    }

    @Test
    fun aReencodeHasTheAskedSizeColoursAndFrames() = runTest {
        val w = 400
        val h = 240
        // 25 frames at 4 cs (25 fps), a moving gradient: many colours.
        val writer = GifWriter(w, h, IntArray(255) { (0xFF shl 24) or (it shl 16) or ((255 - it) shl 8) or (it / 2) }, loopCount = 0)
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
