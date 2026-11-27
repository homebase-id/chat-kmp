package id.homebase.api.video

import id.homebase.chat.TestTempDirs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue

// Lives in homebase-chat because that module's resources carry the bundled desktop ffmpeg.
class GifShrinkerJvmTest {

    @Test
    fun aGifIsShrunkUnderBudgetAndStaysAnimated_asFfprobeReadsIt() = runTest {
        assumeTrue("FFmpeg binaries not bundled in this test classpath", FFmpegBinaryManager.isAvailable())
        val dir = TestTempDirs.dir("gif-shrink").toFile()
        try {
            val source = File(dir, "source.gif")
            exec(
                FFmpegBinaryManager.ffmpegPath(), "-nostdin", "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=20:duration=1", source.path,
            )
            val input = source.readBytes()

            val budget = input.size / 2L
            val output = GifShrinker.shrink(input, budget)

            assertTrue(output.size <= budget, "input=${input.size} B output=${output.size} B budget=$budget B")
            assertEquals("GIF", output.decodeToString(0, 3))
            val shrunk = File(dir, "shrunk.gif").apply { writeBytes(output) }
            val (width, height, frames) = exec(
                FFmpegBinaryManager.ffprobePath(), "-v", "error", "-count_frames", "-select_streams", "v:0",
                "-show_entries", "stream=width,height,nb_read_frames", "-of", "csv=p=0", shrunk.path,
            ).trim().split(",").map { it.toInt() }
            // One of the ladder's steps: 512 or 384 px wide, at the source's 20 fps or lowered to 12.
            assertTrue(Triple(width, height, frames) in setOf(Triple(512, 288, 20), Triple(384, 216, 20), Triple(384, 216, 12)), "$width x $height, $frames frames")
            assertTrue(frames > 1)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun exec(vararg command: String): String {
        val process = FFmpegBinaryManager.processBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "${command.first()} failed: $output" }
        return output
    }
}
