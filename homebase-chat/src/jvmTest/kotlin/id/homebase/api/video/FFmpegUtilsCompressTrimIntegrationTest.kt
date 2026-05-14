package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import id.homebase.api.file.safeDeleteRecursively
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue

/**
 * End-to-end check that `compressVideo`'s `-ss` / `-t` arg threading produces
 * an output of the expected duration. This is the only test that exercises
 * the actual JVM ProcessBuilder + libx264 pipeline; all the data-plumbing
 * tests are unit tests above.
 *
 * Skips silently when the bundled FFmpeg binary isn't on the classpath
 * (e.g. CI building only homebase-api without the homebase-chat resources).
 * Run locally with:
 *   ./gradlew homebase-api:jvmTest --tests "*FFmpegUtilsCompressTrim*"
 */
class FFmpegUtilsCompressTrimIntegrationTest {

    @Test
    fun trim_writesShorterOutput_withCorrectDuration() = runTest {
        assumeTrue(
            "FFmpeg binaries not bundled in this test classpath",
            FFmpegBinaryManager.isAvailable(),
        )

        val fixturePath = VideoTestHelper.copyToTempFile("sample.mp4")
        try {
            // Trim 1.0 s → 4.0 s (3 second window) on a 6 s fixture.
            val output = FFmpegUtils.compressVideo(
                inputPath = fixturePath,
                trimStartMs = 1_000L,
                trimEndMs = 4_000L,
            )
            assertNotNull(output, "compressVideo must produce a trimmed output")

            try {
                val outDur = FFmpegUtils.getDurationMs(output)
                assertTrue(
                    outDur in 2_700L..3_300L,
                    "Expected ~3000 ms after trim, got ${outDur}ms",
                )
            } finally {
                File(output).delete()
            }
        } finally {
            File(fixturePath).delete()
        }
    }

    @Test
    fun nullTrim_skipsCompression_whenAlreadyOptimal() = runTest {
        // The fixture is 6 s, 320×180, h264, ~26 KB → avg ~35 kbps. Far below
        // MAX_WIDTH (1280) and MAX_BITRATE (3 Mbps). With no trim requested,
        // compressVideo must short-circuit to null rather than re-encoding.
        assumeTrue(
            "FFmpeg binaries not bundled in this test classpath",
            FFmpegBinaryManager.isAvailable(),
        )
        val fixturePath = VideoTestHelper.copyToTempFile("sample.mp4")
        try {
            val result = FFmpegUtils.compressVideo(
                inputPath = fixturePath,
                trimStartMs = null,
                trimEndMs = null,
            )
            assertNull(result, "Already-optimal video without trim must skip compression")
        } finally {
            File(fixturePath).delete()
        }
    }

    @Test
    fun trimFromMiddleStart_producesCorrectDuration() = runTest {
        // Crucial coverage: -ss before -i. If the start arg moves to after -i,
        // the encode begins at t=0 and the duration check still passes when the
        // window happens to start at zero — only a non-zero start exposes that.
        assumeTrue(
            "FFmpeg binaries not bundled in this test classpath",
            FFmpegBinaryManager.isAvailable(),
        )
        val fixturePath = VideoTestHelper.copyToTempFile("sample.mp4")
        try {
            // Trim 2.0 s → 5.0 s out of the 6 s fixture.
            val output = FFmpegUtils.compressVideo(
                inputPath = fixturePath,
                trimStartMs = 2_000L,
                trimEndMs = 5_000L,
            )
            assertNotNull(output, "compressVideo with trim must produce output")
            try {
                val outDur = FFmpegUtils.getDurationMs(output)
                assertTrue(
                    outDur in 2_700L..3_300L,
                    "Expected ~3000 ms after trim, got ${outDur}ms",
                )
            } finally {
                File(output).delete()
            }
        } finally {
            File(fixturePath).delete()
        }
    }

    /**
     * End-to-end check of HLS segment-and-encrypt: the output is genuinely
     * AES-128 encrypted, and the plaintext key material FFmpeg needed on disk
     * during segmentation is deleted afterwards (issue #7).
     */
    @Test
    fun segmentAndEncrypt_producesEncryptedHls_andDeletesKeyMaterial() = runTest {
        assumeTrue(
            "FFmpeg binaries not bundled in this test classpath",
            FFmpegBinaryManager.isAvailable(),
        )

        val fixturePath = VideoTestHelper.copyToTempFile("sample.mp4")
        try {
            val result = FFmpegUtils.segmentAndEncryptVideo(
                inputPath = fixturePath,
                keyHeader = KeyHeader.newRandom16(),
                onProgress = null,
            )
            assertNotNull(result, "segmentAndEncryptVideo must produce an HLS playlist + segment")
            val (playlistPath, segmentPath) = result
            val outputDir = File(playlistPath).parentFile!!

            try {
                // The encrypted segment + playlist exist...
                assertTrue(File(playlistPath).exists(), "index.m3u8 must exist")
                assertTrue(File(segmentPath).exists(), "index.ts must exist")

                // ...the playlist proves the segment really was AES-128 encrypted, so the
                // key-cleanup assertion below can't "pass" by silently skipping encryption...
                val playlist = File(playlistPath).readText()
                assertTrue(
                    playlist.contains("#EXT-X-KEY") && playlist.contains("METHOD=AES-128"),
                    "playlist must declare AES-128 encryption, was:\n$playlist",
                )

                // ...and the plaintext key material FFmpeg needed during segmentation is
                // gone — it must not linger in the cache dir (issue #7).
                assertFalse(File(outputDir, "enc.key").exists(), "enc.key must be deleted after segmentation")
                assertFalse(File(outputDir, "keyinfo.txt").exists(), "keyinfo.txt must be deleted after segmentation")
            } finally {
                safeDeleteRecursively(outputDir.parent, outputDir.name)
            }
        } finally {
            File(fixturePath).delete()
        }
    }

    /**
     * A failed segmentation must not leak its `hls_<uuid>/` working directory
     * into the cache dir (issue #5).
     */
    @Test
    fun segmentAndEncrypt_failedInput_leavesNoOutputDirBehind() = runTest {
        assumeTrue(
            "FFmpeg binaries not bundled in this test classpath",
            FFmpegBinaryManager.isAvailable(),
        )

        // Garbage bytes with a video extension — FFmpeg cannot possibly segment this.
        val corrupt = File.createTempFile("corrupt_", ".mp4")
        corrupt.writeBytes(ByteArray(2048) { it.toByte() })

        val tmpDir = File(System.getProperty("java.io.tmpdir"))
        fun hlsDirNames(): Set<String> =
            (tmpDir.listFiles { f -> f.isDirectory && f.name.startsWith("hls_") } ?: emptyArray())
                .map { it.name }
                .toSet()
        val before = hlsDirNames()

        try {
            assertFailsWith<VideoSegmentException> {
                FFmpegUtils.segmentAndEncryptVideo(
                    inputPath = corrupt.absolutePath,
                    keyHeader = KeyHeader.newRandom16(),
                    onProgress = null,
                )
            }
            // The hls_<uuid>/ dir created for the failed run must be cleaned up,
            // not leaked into the cache dir (issue #5).
            assertEquals(
                before,
                hlsDirNames(),
                "a failed segmentation must not leave an hls_<uuid>/ directory behind",
            )
        } finally {
            corrupt.delete()
        }
    }
}
