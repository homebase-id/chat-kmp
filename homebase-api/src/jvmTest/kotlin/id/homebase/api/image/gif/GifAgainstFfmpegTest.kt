package id.homebase.api.image.gif

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** With an ffmpeg on the machine: our decoder reads GIFs as ffmpeg does, and ffmpeg reads ours as we do. */
class GifAgainstFfmpegTest {
    private val dir = Files.createTempDirectory("gif").toFile()
    private val ffmpeg = "ffmpeg".takeIf { runCatching { ProcessBuilder(it, "-version").start().waitFor() == 0 }.getOrDefault(false) }

    private fun run(vararg command: String): ByteArray {
        val process = ProcessBuilder(*command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val out = process.inputStream.readBytes()
        assertEquals(0, process.waitFor(), command.joinToString(" "))
        return out
    }

    /** ffmpeg's composited frames as ARGB, transparent pixels as 0. */
    private fun ffmpegFrames(gif: ByteArray, w: Int, h: Int): List<IntArray> {
        val file = File(dir, "in.gif").apply { writeBytes(gif) }
        val raw = run(ffmpeg!!, "-v", "error", "-i", file.path, "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "rgba", "-")
        return (0 until raw.size / (w * h * 4)).map { f ->
            IntArray(w * h) { i ->
                val p = (f * w * h + i) * 4
                val a = raw[p + 3].toInt() and 0xFF
                if (a == 0) 0 else (a shl 24) or ((raw[p].toInt() and 0xFF) shl 16) or ((raw[p + 1].toInt() and 0xFF) shl 8) or (raw[p + 2].toInt() and 0xFF)
            }
        }
    }

    private fun ourFrames(gif: ByteArray): Triple<Int, Int, List<IntArray>> {
        val decoder = assertNotNull(GifDecoder.open(gif))
        return Triple(decoder.width, decoder.height, decode(gif))
    }

    private fun assertSameAsFfmpeg(gif: ByteArray, name: String) {
        val (w, h, ours) = ourFrames(gif)
        val theirs = ffmpegFrames(gif, w, h)
        assertEquals(theirs.size, ours.size, "$name: frames")
        for ((f, pair) in ours.zip(theirs).withIndex()) {
            val differing = pair.first.indices.count { pair.first[it] != pair.second[it] }
            assertEquals(0, differing, "$name frame $f: $differing of ${w * h} pixels differ")
        }
    }

    private fun ffmpegGif(vararg filter: String): ByteArray {
        val out = File(dir, "made.gif")
        run(ffmpeg!!, "-v", "error", "-y", *filter, out.path)
        return out.readBytes()
    }

    @Test
    fun ourDecoderReadsGifsAsFfmpegDoes() {
        ffmpeg ?: return
        assertSameAsFfmpeg(javaClass.getResourceAsStream("/test_images/mountain_800.gif")!!.readBytes(), "mountain_800")
        // ffmpeg's own encoder stores changed rectangles over earlier frames, the way most animated GIFs are made.
        assertSameAsFfmpeg(ffmpegGif("-f", "lavfi", "-i", "testsrc2=size=160x120:rate=10:duration=2"), "testsrc2")
        // Transparency, cleared between frames.
        assertSameAsFfmpeg(
            ffmpegGif(
                "-f", "lavfi", "-i", "testsrc2=size=160x120:rate=10:duration=1,format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='if(gt(mod(X+N*8,160),80),0,255)'",
                "-filter_complex", "split[a][b];[a]palettegen=reserve_transparent=1[p];[b][p]paletteuse=alpha_threshold=128",
            ),
            "transparent",
        )
    }

    @Test
    fun ffmpegReadsOurReencodesAsWeDo() = kotlinx.coroutines.runBlocking {
        ffmpeg ?: return@runBlocking
        for ((name, source) in listOf(
            "testsrc2" to ffmpegGif("-f", "lavfi", "-i", "testsrc2=size=320x240:rate=20:duration=1"),
            "mountain_800" to javaClass.getResourceAsStream("/test_images/mountain_800.gif")!!.readBytes(),
        )) {
            val (w, h, _) = ourFrames(source)
            val out = assertNotNull(reencodeGif(source, w / 2, h / 2, colors = 64, fps = 12), name)
            assertSameAsFfmpeg(out, "$name re-encoded")
            assertTrue(out.size < source.size, "$name: ${out.size} B from ${source.size} B")
        }
    }
}
