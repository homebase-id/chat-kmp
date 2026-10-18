package id.homebase.api.video

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class RemuxHlsToMp4JvmTest {

    @Test
    fun remuxesTwoSegmentHlsToMp4() = runTest {
        assertTrue(FFmpegBinaryManager.isAvailable(), "bundled ffmpeg must be on the homebase-chat jvmTest classpath")

        val dir = Files.createTempDirectory("remux_hls_test").toFile()
        try {
            val playlist = File(dir, "index.m3u8")
            val gen = FFmpegBinaryManager.processBuilder(
                FFmpegBinaryManager.ffmpegPath(), "-y",
                "-f", "lavfi", "-i", "testsrc=size=160x90:rate=10:duration=2",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=2",
                "-c:v", "libx264", "-g", "10", "-pix_fmt", "yuv420p", "-c:a", "aac",
                "-f", "hls", "-hls_time", "1", "-hls_list_size", "0",
                "-hls_segment_filename", File(dir, "seg%d.ts").absolutePath,
                playlist.absolutePath,
            ).redirectErrorStream(true).start()
            val genOut = gen.inputStream.bufferedReader().readText()
            assertEquals(0, gen.waitFor(), "fixture generation failed:\n$genOut")
            assertEquals(2, dir.listFiles { f -> f.extension == "ts" }!!.size, "fixture must have two segments")

            val out = File(dir, "out.mp4")
            assertTrue(FFmpegUtils.remuxHlsToMp4(playlist.absolutePath, out.absolutePath))
            assertTrue(out.length() > 0L)
            val durationMs = FFmpegUtils.getDurationMs(out.absolutePath)
            assertTrue(durationMs in 1_500L..2_600L, "expected ~2000ms, got ${durationMs}ms")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun remuxesSingleFileByteRangePlaylistOnce() = runTest {
        assertTrue(FFmpegBinaryManager.isAvailable(), "bundled ffmpeg must be on the homebase-chat jvmTest classpath")

        val dir = Files.createTempDirectory("remux_hls_single").toFile()
        try {
            val generated = File(dir, "gen.m3u8")
            val gen = FFmpegBinaryManager.processBuilder(
                FFmpegBinaryManager.ffmpegPath(), "-y",
                "-f", "lavfi", "-i", "testsrc=size=160x90:rate=10:duration=4",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=4",
                "-c:v", "libx264", "-g", "10", "-pix_fmt", "yuv420p", "-c:a", "aac",
                "-f", "hls", "-hls_time", "1", "-hls_list_size", "0", "-hls_flags", "single_file",
                "-hls_segment_filename", File(dir, "gen.ts").absolutePath,
                generated.absolutePath,
            ).redirectErrorStream(true).start()
            val genOut = gen.inputStream.bufferedReader().readText()
            assertEquals(0, gen.waitFor(), "fixture generation failed:\n$genOut")
            val generatedLines = generated.readLines()
            assertTrue(generatedLines.count { it.startsWith("#EXT-X-BYTERANGE") } > 1, "fixture must have several byte ranges")

            // Same shape MediaDownloadHandler writes: every segment line points at the one decrypted .ts.
            val tsName = "input_hlsdl_test.ts"
            File(dir, "gen.ts").renameTo(File(dir, tsName))
            val playlist = File(dir, "input_hlsdl_test.m3u8")
            playlist.writeText(
                generatedLines.filter { !it.startsWith("#EXT-X-KEY") }
                    .joinToString("\n") { if (it.isNotBlank() && !it.startsWith("#")) tsName else it },
            )

            val out = File(dir, "out.mp4")
            assertTrue(FFmpegUtils.remuxHlsToMp4(playlist.absolutePath, out.absolutePath))
            val durationMs = FFmpegUtils.getDurationMs(out.absolutePath)
            assertTrue(durationMs in 3_500L..4_600L, "expected ~4000ms (video exactly once), got ${durationMs}ms")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun returnsFalseWhenPlaylistMissing() = runTest {
        val dir = Files.createTempDirectory("remux_hls_missing").toFile()
        try {
            val out = File(dir, "out.mp4")
            assertFalse(FFmpegUtils.remuxHlsToMp4(File(dir, "nope.m3u8").absolutePath, out.absolutePath))
            assertFalse(out.exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
