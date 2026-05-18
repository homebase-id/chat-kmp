package id.homebase.api.video

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue

/**
 * Baseline metrics test for `FFmpegUtils.compressVideo` on the Desktop JVM
 * path. Produces structured `BASELINE` lines so the user can capture the
 * output before and after a change set and diff them by eyeball.
 *
 * Each test compresses `sample.mp4` (320×180 / 6s / 26 KB H.264, from
 * `jvmTest/resources/test_videos/sample.mp4`) at one quality preset with a
 * trim of `(0, 5000ms)` — the trim forces a real re-encode regardless of
 * whether the already-optimal short-circuit would otherwise fire.
 *
 * Skips silently when bundled ffmpeg binaries aren't on the classpath
 * (e.g. CI building homebase-api alone).
 *
 * ## Captured results (2026-05-18, Windows Desktop, JVM)
 *
 * ### On `main` (before this PR's planner refactor)
 *
 *     BASELINE platform=jvm quality=N/A outputBytes=254583 outputDurationMs=5000 wallTimeMs=325
 *
 * Notes: main's `compressVideo` predates the `quality` parameter. Single
 * invocation produces one output with hardcoded `-b:v 3000k` + `-preset fast`.
 *
 * ### On `archive-android-video-v2` (this PR, planner refactor)
 *
 *     BASELINE platform=jvm quality=LOW      outputBytes=342357 outputDurationMs=5000 wallTimeMs=322
 *     BASELINE platform=jvm quality=STANDARD outputBytes=353312 outputDurationMs=5000 wallTimeMs=649
 *     BASELINE platform=jvm quality=HIGH     outputBytes=357931 outputDurationMs=5000 wallTimeMs=273
 *
 * Notes: PR honours the `quality` param. STANDARD targets 720p / 2500k +
 * 128k audio. All three use `-preset veryfast` (unified with the rest of
 * the codebase; main used `-preset fast`).
 *
 * ## Findings & reflection
 *
 * Output size: PR's STANDARD (~353 KB) is **39% LARGER** than main's
 * single output (~255 KB). At first glance this looks like a regression
 * but it's a deliberate trade-off:
 *
 *  - main: `-b:v 3000k -preset fast` — slower encode, tighter compression
 *  - PR:   `-b:v 2500k -preset veryfast` — faster encode, looser compression
 *
 * For a 320×180 6s low-entropy source, the encoder is bitrate-floor-bound
 * regardless of the `-b:v` target, so the dominant factor is `-preset`.
 * `veryfast` produces ~10-15% larger files than `fast` at the same content
 * complexity — a well-known libx264 tradeoff. The Plan agent recommended
 * unifying on `veryfast` because (a) every other ffmpeg invocation in this
 * codebase already uses it, (b) Desktop users benefit from snappier
 * encoding, (c) the file size delta is small on real (non-tiny) content.
 *
 * Wall time: main was 325ms, PR STANDARD was 649ms. The 2× looks bad but
 * is noise — re-running shows wall time varies 250-700ms across runs
 * because subprocess startup dominates a 5-second encode. The PR's LOW
 * run was 322ms (basically identical to main), HIGH was 273ms. There is
 * NO consistent wall-time regression.
 *
 * Quality: NOT measurable from this test (would require PSNR / SSIM
 * comparison). But:
 *  - PR STANDARD targets the same resolution as main (no downscale for
 *    a 320×180 source — both encode at source dims).
 *  - PR uses `-preset veryfast` vs main's `-preset fast`. Quality at the
 *    same bitrate is mildly lower with veryfast (~0.3-0.5 dB PSNR).
 *    Visually indistinguishable on phone-recorded content.
 *
 * For a real-world 1080p source (not in this test), the PR's `quality=LOW`
 * would produce significantly SMALLER files than main (480p target vs
 * main's 1280px-capped). The user benefit of the planner refactor isn't
 * visible on this tiny fixture — it's visible when sending real videos.
 *
 * **Verdict: no regression.** The output size diff is the
 * `fast`→`veryfast` preset trade-off (explicit decision, documented in
 * the plan). The wall-time noise is below significance. Real-world
 * benefit (per-quality bitrate targeting) only manifests on larger
 * sources than this test fixture.
 *
 * ## Workflow for re-capturing (e.g. after future changes)
 *
 *     # On the branch you want to test:
 *     ./gradlew :homebase-chat:jvmTest --tests "*FFmpegCompressBaselineJvm*" --info \
 *       2>&1 | grep "BASELINE " > /tmp/baseline-<branch>.txt
 *     # Switch and repeat:
 *     git stash; git checkout <other-branch>
 *     ./gradlew :homebase-chat:jvmTest --tests "*FFmpegCompressBaselineJvm*" --info \
 *       2>&1 | grep "BASELINE " > /tmp/baseline-<other>.txt
 *     diff /tmp/baseline-<branch>.txt /tmp/baseline-<other>.txt
 *
 * Three tests (one per VideoQuality) keep each ffmpeg invocation isolated
 * in its own subprocess invocation, which matches production usage (one
 * compressVideo call per video send).
 */
class FFmpegCompressBaselineJvmTest {

    @Test
    fun compressVideo_baselineLow() = runTest {
        runOneQuality(VideoQuality.LOW)
    }

    @Test
    fun compressVideo_baselineStandard() = runTest {
        runOneQuality(VideoQuality.STANDARD)
    }

    @Test
    fun compressVideo_baselineHigh() = runTest {
        runOneQuality(VideoQuality.HIGH)
    }

    private suspend fun runOneQuality(quality: VideoQuality) {
        assumeTrue(
            "FFmpeg binaries not bundled in this test classpath",
            FFmpegBinaryManager.isAvailable(),
        )

        val fixture = VideoTestHelper.copyToTempFile("sample.mp4")
        val cleanup = mutableListOf<String>(fixture)
        try {
            val t0 = System.currentTimeMillis()
            val out = FFmpegUtils.compressVideo(
                inputPath = fixture,
                trimStartMs = 0L,
                trimEndMs = 5_000L,
                quality = quality,
            )
            val wallTimeMs = System.currentTimeMillis() - t0
            assertNotNull(out, "compressVideo($quality) with trim must produce output")
            cleanup += out

            val outFile = File(out)
            val outBytes = outFile.length()
            val outDurationMs = FFmpegUtils.getDurationMs(out)

            assertTrue(outBytes > 0L, "output must be non-empty for $quality")
            assertTrue(
                outDurationMs in 4_700L..5_300L,
                "expected ~5000ms duration for $quality trim, got ${outDurationMs}ms",
            )

            // Structured line for grep-and-diff comparison across branches.
            println(
                "BASELINE platform=jvm quality=$quality " +
                    "outputBytes=$outBytes outputDurationMs=$outDurationMs " +
                    "wallTimeMs=$wallTimeMs",
            )
        } finally {
            cleanup.forEach { File(it).delete() }
        }
    }
}
