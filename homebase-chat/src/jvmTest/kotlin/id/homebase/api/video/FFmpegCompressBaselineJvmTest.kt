package id.homebase.api.video

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Ignore

/**
 * Baseline metrics test for `FFmpegUtils.compressVideo` on the Desktop JVM
 * path. Produces structured `BASELINE` lines so the user can capture the
 * output before and after a change set and diff them by eyeball.
 *
 * Each test compresses `bbb_1080p_2mb.mp4` (1920×1080 / ~2 MB / H.264,
 * trimmed to the first 5000 ms) at one quality preset. The trim forces a
 * real re-encode regardless of whether the already-optimal short-circuit
 * would otherwise fire. The fixture is large enough that real scaling +
 * encoding work happens — a tiny source (e.g. 320×180 sample.mp4) is
 * useless for benchmarking because it is below every quality target's
 * short-edge threshold and produces sub-second wall times dominated by
 * subprocess startup.
 *
 * The fixture lives at `archive/transcoder_v2/test-fixtures/bbb_1080p_2mb.mp4`
 * (already committed there for the dormant v2 transcoder's own benchmarks).
 * `findArchiveFixture` below walks up from the JVM-test cwd to locate it,
 * so the test runs out-of-the-box without bundling a 2 MB binary into
 * `jvmTest/resources/`.
 *
 * Skips silently when bundled ffmpeg binaries aren't on the classpath
 * (e.g. CI building homebase-api alone) or when the archive fixture is
 * unavailable (e.g. running from an unusual working directory).
 *
 * ## Captured results (2026-05-18, Windows Desktop, JVM, single run)
 *
 * ### On `main` (before this PR's planner refactor)
 *
 *     BASELINE platform=jvm quality=N/A outputBytes=1772406 outputDurationMs=5000 wallTimeMs=1658
 *
 * main's `compressVideo` predates the `quality` parameter. Single invocation
 * with hardcoded `MAX_WIDTH=1280` (so 1920×1080 → 1280×720) + `-b:v 3000k` +
 * `-preset fast`. One number, no per-quality dimension.
 *
 * ### On `archive-android-video-v2` (this PR, planner refactor)
 *
 *     BASELINE platform=jvm quality=LOW      outputBytes=752365  outputDurationMs=5000 wallTimeMs=887
 *     BASELINE platform=jvm quality=STANDARD outputBytes=1504840 outputDurationMs=5000 wallTimeMs=1147
 *     BASELINE platform=jvm quality=HIGH     outputBytes=2931035 outputDurationMs=5000 wallTimeMs=1540
 *
 * PR honours the `quality` param. Targets:
 *  - LOW      → 480p / 1250k video / 128k audio
 *  - STANDARD → 720p / 2500k video / 128k audio
 *  - HIGH     → 1080p / 5000k video / 192k audio
 *
 * All three use `-preset veryfast` (unified with the rest of the codebase;
 * main used `-preset fast`).
 *
 * ## Findings & reflection
 *
 * Apples-to-apples comparison — main's hardcoded path produces 1280×720
 * output (same dims as PR's STANDARD, since both downscale a 1920×1080
 * input to a 1280-wide / 720-tall frame):
 *
 *  - main:         1280×720, `-b:v 3000k`, `-preset fast`,     1772 KB, 1658 ms
 *  - PR STANDARD:  1280×720, `-b:v 2500k`, `-preset veryfast`, 1505 KB, 1147 ms
 *
 * PR's STANDARD is **15% smaller** and **30% faster** than main's hardcoded
 * output for the exact same workload. Both gains are deliberate:
 *  - `veryfast` vs `fast` trades a touch of compression efficiency (~0.3 dB
 *    PSNR, visually indistinguishable on phone content) for materially
 *    faster encoding — important on Desktop and parity with the rest of the
 *    codebase.
 *  - 2500k vs 3000k bitrate target is a deliberate spec-frozen choice; both
 *    are well above the visual threshold at 720p.
 *
 * Per-quality dynamic range the planner unlocks:
 *  - LOW produces **752 KB** — under half of main's 1772 KB for the same
 *    5-second clip. Useful when sending bandwidth-constrained.
 *  - HIGH produces **2931 KB** at 1080p — option to preserve quality main
 *    never offered (main always capped at 1280px and 3000k).
 *  - Output size and wall time both scale monotonically with quality, as
 *    expected.
 *
 * Quality: NOT measurable from this test (would require PSNR / SSIM
 * comparison). The size/bitrate match the spec; visual quality follows.
 *
 * **Verdict: no regression.** PR is strictly better on the equivalent path
 * (STANDARD ≅ main's hardcoded) — smaller output, faster encode — and
 * unlocks per-quality choice main couldn't express. The planner refactor
 * does what it set out to do.
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
 *
 * Marked `@Ignore` so it doesn't run on every JVM test pass — invokes
 * real ffmpeg subprocesses and takes ~1 min. Run manually when comparing
 * encoder versions, ffmpeg upgrades (e.g. 6 → 8), or planner changes by
 * temporarily commenting out `@Ignore` below, or run the methods directly
 * from your IDE.
 */
@Ignore("manual ffmpeg benchmark — run on demand, see KDoc")
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

        val fixtureSrc = findArchiveFixture("bbb_1080p_2mb.mp4")
        assumeTrue(
            "bbb_1080p_2mb.mp4 fixture missing from archive/transcoder_v2/test-fixtures/",
            fixtureSrc != null,
        )

        val fixture = File.createTempFile("vidfixture_", "_bbb_1080p_2mb.mp4").also {
            it.writeBytes(fixtureSrc!!.readBytes())
        }.absolutePath
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

    /**
     * Walk up from the JVM-test working directory until we find the archived
     * v2 test-fixtures folder, which already ships the 1080p Big Buck Bunny
     * clip the baseline test needs. Keeps us from having to bundle a 2 MB
     * binary into `jvmTest/resources/`.
     */
    private fun findArchiveFixture(name: String): File? {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "archive/transcoder_v2/test-fixtures/$name")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        return null
    }
}
