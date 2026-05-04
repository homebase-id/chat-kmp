package id.homebase.api.video

import java.io.File
import kotlin.test.Test
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
}
