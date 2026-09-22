package id.homebase.api.video

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue

// Lives in homebase-chat because that module's resources carry the bundled desktop ffmpeg.
class GifShrinkerJvmTest {

    @Test
    fun realFfmpeg_shrinksAGifUnderBudgetAndKeepsItAnimated() = runTest {
        assumeTrue("FFmpeg binaries not bundled in this test classpath", FFmpegBinaryManager.isAvailable())
        val dir = kotlin.io.path.createTempDirectory("gif-shrink").toFile()
        try {
            val source = File(dir, "source.gif")
            exec(
                FFmpegBinaryManager.ffmpegPath(), "-nostdin", "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=20:duration=1", source.path,
            )
            val input = source.readBytes()

            // Lowered budget: only the last step (384 px, 64 colours, 12 fps) gets under it.
            val budget = input.size / 2L
            val output = GifShrinker.shrink(input, budget)

            assertTrue(output.size <= budget, "input=${input.size} B output=${output.size} B budget=$budget B")
            assertEquals("GIF", output.decodeToString(0, 3))
            val shrunk = File(dir, "shrunk.gif").apply { writeBytes(output) }
            val (width, height, frames) = exec(
                FFmpegBinaryManager.ffprobePath(), "-v", "error", "-count_frames", "-select_streams", "v:0",
                "-show_entries", "stream=width,height,nb_read_frames", "-of", "csv=p=0", shrunk.path,
            ).trim().split(",").map { it.toInt() }
            assertEquals(384 to 216, width to height)
            assertEquals(12, frames)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun exec(vararg command: String): String {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "${command.first()} failed: $output" }
        return output
    }
}
