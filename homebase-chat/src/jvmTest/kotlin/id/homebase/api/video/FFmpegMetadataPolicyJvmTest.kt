package id.homebase.api.video

import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// A transcode keeps the camera's location and capture time when the app keeps metadata, and drops them when it strips.
class FFmpegMetadataPolicyJvmTest {

    private val location = "+37.7749-122.4194/"
    private val created = "2026-11-14T08:31:25.000000Z"

    private fun ffmpeg(vararg args: String): String {
        val process = FFmpegBinaryManager.processBuilder(*args).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { out }
        return out
    }

    private fun tags(path: String): Map<String, String> =
        ffmpeg(FFmpegBinaryManager.ffprobePath(), "-v", "error", "-show_entries", "format_tags", "-of", "default=noprint_wrappers=1", path)
            .lines().filter { it.startsWith("TAG:") }.associate { it.removePrefix("TAG:").substringBefore('=') to it.substringAfter('=') }

    @Test
    fun aTranscodeKeepsOrDropsTheLocationAsTheAppSays() = runTest {
        assumeTrue("FFmpeg binaries not bundled in this test classpath", FFmpegBinaryManager.isAvailable())
        val sample = VideoTestHelper.copyToTempFile("sample.mp4")
        val tagged = File.createTempFile("geotagged_", ".mov").absolutePath
        val outputs = mutableListOf<String>()
        try {
            ffmpeg(
                FFmpegBinaryManager.ffmpegPath(), "-y", "-i", sample, "-c", "copy",
                "-metadata", "location=$location", "-metadata", "creation_time=$created", tagged,
            )
            assertEquals(location, tags(tagged)["location"], "the fixture carries a location")

            val kept = FFmpegUtils.compressVideo(tagged, quality = VideoQuality.STANDARD, keepMetadata = true).also { outputs += it }
            assertEquals(location, tags(kept)["location"], "an owner's library keeps where it was filmed")
            assertEquals(created, tags(kept)["creation_time"], "and when")

            val stripped = FFmpegUtils.compressVideo(tagged, quality = VideoQuality.STANDARD, keepMetadata = false).also { outputs += it }
            assertNull(tags(stripped)["location"], "a sent clip doesn't carry the sender's location")
        } finally {
            (outputs + sample + tagged).forEach { File(it).delete() }
        }
    }
}
