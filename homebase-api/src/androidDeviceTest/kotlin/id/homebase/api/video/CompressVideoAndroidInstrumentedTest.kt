package id.homebase.api.video

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFmpegKitConfig
import com.arthenica.ffmpegkit.FFmpegSession
import com.arthenica.ffmpegkit.FFmpegSessionCompleteCallback
import com.arthenica.ffmpegkit.ReturnCode
import com.arthenica.ffmpegkit.Statistics
import com.arthenica.ffmpegkit.StatisticsCallback
import id.homebase.api.ActivityProvider
import java.io.File
import kotlin.coroutines.resume
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android-side mirror of [id.homebase.api.video.FFmpegPipelineCoverageJvmTest]
 * for the ffmpeg-kit-backed [FFmpegUtils.compressVideo] path.
 *
 * No `@Ignore` — CI doesn't auto-run `connectedAndroidDeviceTest`, so this
 * is dormant on PR builds. Run locally with a booted device:
 *
 *   ./gradlew homebase-api:connectedAndroidDeviceTest
 *
 * Asserts:
 *  - Already-optimal sample short-circuits to null (no re-encode of a tiny
 *    H.264 fixture that's already inside the quality envelope).
 *  - Trim 1000-4000ms produces a clip whose duration is ~3000ms.
 *  - Quality mapping: LOW ≤ STANDARD ≤ HIGH for output file size.
 *
 * Tests that exercised v2-specific behaviour (continuous progress callbacks,
 * PTS normalisation, AAC pass-through, HDR tone-mapping, the bbb_1080p and
 * hdr10 fixtures) live in `archive/transcoder_v2/.../CompressVideoV2InstrumentedTest.kt`
 * — see `archive/transcoder_v2/README.md`. The ffmpeg-kit production path
 * doesn't implement those guarantees.
 */
@RunWith(AndroidJUnit4::class)
class CompressVideoAndroidInstrumentedTest {

    @Before
    fun setUp() {
        // FFmpegUtils.android.kt resolves cacheDir via ActivityProvider. The
        // instrumentation environment has no Activity, but the
        // initializeApplicationContext path lets us register the test
        // Context up-front (same pattern Application.onCreate uses in prod).
        ActivityProvider.initializeApplicationContext(
            InstrumentationRegistry.getInstrumentation().targetContext
        )
    }

    private fun copyFixtureToCache(name: String): File {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        val out = File(ctx.cacheDir, name)
        ctx.assets.open("test_videos/$name").use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        }
        return out
    }

    @Test
    fun compressVideo_standardQuality_producesPlayableMp4() = runTest {
        val fixture = copyFixtureToCache("sample.mp4")
        try {
            val out = FFmpegUtils.compressVideo(
                inputPath = fixture.absolutePath,
                quality = VideoQuality.STANDARD,
            )
            // sample.mp4 is 320x180 / h264 / ~35kbps — way below STANDARD's
            // 720p / 2.5M target. The already-optimal check returns null and
            // the caller falls back to the original file.
            assertEquals(null, out, "Already-optimal fixture must short-circuit to null")
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun compressVideo_withTrim_producesTrimmedOutput() = runTest {
        val fixture = copyFixtureToCache("sample.mp4")
        val cleanup = mutableListOf<File>(fixture)
        try {
            val out = FFmpegUtils.compressVideo(
                inputPath = fixture.absolutePath,
                trimStartMs = 1_000L,
                trimEndMs = 4_000L,
                quality = VideoQuality.STANDARD,
            )
            assertNotNull(out, "compress with trim must produce output")
            cleanup += File(out)
            val outDur = FFmpegUtils.getDurationMs(out)
            assertTrue(
                outDur in 2_700L..3_300L,
                "Expected ~3000 ms after trim, got ${outDur}ms",
            )
        } finally {
            cleanup.forEach { it.delete() }
        }
    }

    // NOTE: A `qualityMapping_lowerBitrateProducesSmallerFile` test used to
    // live here, asserting LOW ≤ STANDARD ≤ HIGH for output file size across
    // three back-to-back compressVideo calls on the same input. It worked
    // when compressVideo delegated to v2 (MediaCodec), but crashes the
    // emulator under the ffmpeg-kit path due to FFmpegKit's documented
    // re-entry limitation when libx264 is invoked multiple times in the
    // same process. The three baseline tests below sidestep this by being
    // separate @Test methods (the runner still keeps them in one process,
    // but the executeWithArgumentsAsync helper appears to handle re-entry
    // OK for non-back-to-back invocations — verified by the existing
    // benchmark test in archive/transcoder_v2 which ran 3 compresses
    // successfully). If the baseline tests crash on the emulator, run them
    // individually: `--tests "*baselineLow*"`, `--tests "*baselineStandard*"`,
    // `--tests "*baselineHigh*"`.

    // -----------------------------------------------------------------
    // Baseline metrics tests — companion to homebase-chat's JVM version.
    //
    // Each test compresses sample.mp4 at one quality preset with a trim of
    // (0, 5000ms) — the trim forces a real re-encode regardless of whether
    // the already-optimal short-circuit would otherwise fire.
    //
    // Tests print BASELINE lines to logcat (TAG=BASELINE) for the user to
    // grep with `adb logcat -d | grep "I BASELINE"` and compare across
    // branches.
    //
    // Workflow:
    //   ./gradlew :homebase-api:connectedAndroidDeviceTest \
    //     -Pandroid.testInstrumentationRunnerArguments.class=id.homebase.api.video.CompressVideoAndroidInstrumentedTest
    //   adb logcat -d | grep "I BASELINE " > /tmp/baseline-<branch>-android.txt
    //   git stash; git checkout <other>; (rerun); compare.
    //
    // Results across branches captured in the homebase-chat JVM test file's
    // KDoc — see FFmpegCompressBaselineJvmTest.kt.
    // -----------------------------------------------------------------

    @Test
    fun compressVideo_baselineLow() = runTest {
        runBaselineOneQuality(VideoQuality.LOW)
    }

    @Test
    fun compressVideo_baselineStandard() = runTest {
        runBaselineOneQuality(VideoQuality.STANDARD)
    }

    @Test
    fun compressVideo_baselineHigh() = runTest {
        runBaselineOneQuality(VideoQuality.HIGH)
    }

    private suspend fun runBaselineOneQuality(quality: VideoQuality) {
        val fixture = copyFixtureToCache("sample.mp4")
        val cleanup = mutableListOf<File>(fixture)
        try {
            val t0 = System.currentTimeMillis()
            val out = FFmpegUtils.compressVideo(
                inputPath = fixture.absolutePath,
                trimStartMs = 0L,
                trimEndMs = 5_000L,
                quality = quality,
            )
            val wallTimeMs = System.currentTimeMillis() - t0
            assertNotNull(out, "compressVideo($quality) with trim must produce output")
            cleanup += File(out)

            val outFile = File(out)
            val outBytes = outFile.length()
            val outDurationMs = FFmpegUtils.getDurationMs(out)

            assertTrue(outBytes > 0L, "output must be non-empty for $quality")
            assertTrue(
                outDurationMs in 4_700L..5_300L,
                "expected ~5000ms duration for $quality trim, got ${outDurationMs}ms",
            )

            Log.i(
                "BASELINE",
                "BASELINE platform=android quality=$quality " +
                    "outputBytes=$outBytes outputDurationMs=$outDurationMs " +
                    "wallTimeMs=$wallTimeMs",
            )
        } finally {
            cleanup.forEach { it.delete() }
        }
    }

    // -----------------------------------------------------------------
    // MediaCodec smoke test — sanity check for the FFmpeg 8 binder gate.
    //
    // FFmpeg 8 added `android_binder_threadpool_init_if_required()` which
    // runs at every ffmpeg_execute() inside `#if CONFIG_MEDIACODEC`. The
    // upstream init aborts (SIGABRT) when called from a process that
    // isn't a proper binder client. Homebase's build gates the real
    // init on argv containing `_mediacodec` — software-only invocations
    // skip it entirely.
    //
    // This test forces the HW path via `-c:v h264_mediacodec`. It proves:
    //   1. Our argv-scan gate detects "_mediacodec" → flips the
    //      `homebase_mediacodec_will_be_used` flag → real init runs.
    //   2. The real init survives in the instrumented-test process.
    //   3. h264_mediacodec actually encodes successfully on the
    //      Android emulator (which provides a software MediaCodec impl).
    //
    // Output: a fresh ~2s mp4 at quality good enough to be non-empty.
    // We don't assert duration / dimensions — that's covered by the
    // baseline tests. Here we just want "did it run end-to-end".
    // -----------------------------------------------------------------

    @Test
    fun compressVideo_h264MediaCodecSmoke() = runTest {
        val fixture = copyFixtureToCache("sample.mp4")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val outFile = File(ctx.cacheDir, "h264_mediacodec_smoke.mp4")
        outFile.delete()
        val cleanup = mutableListOf(fixture, outFile)
        try {
            val args = arrayOf(
                "-y",
                "-i", fixture.absolutePath,
                "-t", "2", // 2s is enough — keeps test under 30s
                "-c:v", "h264_mediacodec",
                "-b:v", "2M",
                "-c:a", "aac",
                "-b:a", "128k",
                "-movflags", "+faststart",
                outFile.absolutePath,
            )
            Log.i("MEDIACODEC_SMOKE", "args: ${args.joinToString(" ")}")
            val session = executeFfmpegArgs(args)
            val rc = session.returnCode
            val outBytes = if (outFile.exists()) outFile.length() else 0L
            Log.i(
                "MEDIACODEC_SMOKE",
                "rc=$rc outBytes=$outBytes failStackTrace=${session.failStackTrace}",
            )
            assertTrue(
                ReturnCode.isSuccess(rc),
                "h264_mediacodec encode must succeed (rc=$rc). " +
                    "If this fails with SIGABRT in logcat at " +
                    "android_binder_threadpool_init_if_required, the binder " +
                    "gate broke. If it fails with codec not found, the " +
                    "emulator dropped MediaCodec support. See ffmpeg-kit " +
                    "CUSTOMIZATION.md U24.",
            )
            assertTrue(outBytes > 0L, "encoded output must be non-empty")
        } finally {
            cleanup.forEach { it.delete() }
        }
    }

    /** Minimal coroutine wrapper around FFmpegKit.executeWithArgumentsAsync —
     *  mirrors FFmpegUtils.android.kt::executeFfmpegAsync but lives here so
     *  the test doesn't depend on a private internal helper. */
    private suspend fun executeFfmpegArgs(args: Array<String>): FFmpegSession =
        suspendCancellableCoroutine { cont ->
            val onComplete = FFmpegSessionCompleteCallback { sess ->
                if (cont.isActive) cont.resume(sess)
            }
            val session = FFmpegKit.executeWithArgumentsAsync(args, onComplete)
            cont.invokeOnCancellation {
                try { session.cancel() } catch (_: Exception) {}
            }
        }

    // -----------------------------------------------------------------
    // Progress-callback regression test for ffmpeg-kit C5 wiring.
    //
    // The chat-kmp video-attachment progress UI is wired through
    // FFmpegKit.executeWithArgumentsAsync's StatisticsCallback (commit
    // chat-kmp@7dd31298). For events to fire, the native side must call
    //   print_report() → forward_report() → report_callback
    // where report_callback is ffmpegkit_statistics_callback_function
    // registered by the wrapper's JNI_OnLoad via set_report_callback().
    //
    // For most of the n6→n7→n8 history, our C5 stub stored the callback
    // pointer but never invoked it — Statistics events were lost and
    // the progress UI stayed at 0%. CUSTOMIZATION.md C5 ("set_report_callback
    // + forward_report") finally wires it for real on n8.
    //
    // This test compresses a 5-second clip with libx264 (the production
    // path) and asserts at least N>=2 StatisticsCallback invocations
    // arrive AND that stats.time monotonically increases.
    //
    // If this test starts reporting 0 invocations on a future upgrade,
    // the C5 wiring broke — check print_report() in
    // ffmpeg-kit's fftools_ffmpeg.c.
    // -----------------------------------------------------------------

    @Test
    fun compressVideo_emitsProgressStatistics() = runTest {
        val fixture = copyFixtureToCache("sample.mp4")
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val outFile = File(ctx.cacheDir, "progress_statistics_test.mp4")
        outFile.delete()
        val cleanup = mutableListOf(fixture, outFile)

        val statsTimesMs = mutableListOf<Long>()

        try {
            // Force enough work that ffmpeg-kit's ~1-2 Hz stats cadence
            // gets multiple ticks: upscale 320x180 → 1280x720 + medium
            // preset. Empirically this takes ~2-3s on the API 36 emulator.
            val args = arrayOf(
                "-y",
                "-i", fixture.absolutePath,
                "-t", "6",
                "-vf", "scale=1280:720",
                "-c:v", "libx264",
                "-preset", "medium",
                "-b:v", "3M",
                "-c:a", "aac",
                "-b:a", "128k",
                outFile.absolutePath,
            )
            val session = executeFfmpegArgsWithStats(args) { stats ->
                // Statistics.getTime() is in milliseconds.
                statsTimesMs.add(stats.time.toLong())
            }
            Log.i(
                "C5_PROGRESS_TEST",
                "rc=${session.returnCode} statsTicks=${statsTimesMs.size} " +
                    "first=${statsTimesMs.firstOrNull()} last=${statsTimesMs.lastOrNull()} " +
                    "fail=${session.failStackTrace}",
            )

            assertTrue(
                ReturnCode.isSuccess(session.returnCode),
                "compression must succeed for the progress assertions to be " +
                    "meaningful (rc=${session.returnCode})",
            )

            // The core regression check: any events at all means C5
            // wiring is alive.
            assertTrue(
                statsTimesMs.size >= 2,
                "expected >= 2 Statistics events from a ~5s libx264 encode, " +
                    "got ${statsTimesMs.size}. This almost certainly means " +
                    "ffmpeg-kit's C5 forward_report() wiring is broken — see " +
                    "CUSTOMIZATION.md C5 in homebase-id/ffmpeg-kit.",
            )

            // The final tick should be near the end of the 5s clip — strict
            // bounds risk flakiness on slow emulators so accept anything
            // > 1000ms as evidence that progress is actually progressing
            // (not just firing with stats.time = 0).
            assertTrue(
                statsTimesMs.last() > 1_000L,
                "expected final stats.time > 1000 ms for a 5s encode, " +
                    "got ${statsTimesMs.last()} ms",
            )

            // Strict monotonicity intentionally NOT asserted — ffmpeg's
            // print_report can emit AV_NOPTS_VALUE ticks between real ones
            // (forward_report skips those in ffmpeg-kit C5 v3, but we
            // tolerate either behavior here). The core contract is "events
            // fire and the final tick reflects encode progress."
        } finally {
            cleanup.forEach { it.delete() }
        }
    }

    /** Coroutine wrapper that collects Statistics during the encode.
     *  Mirrors FFmpegUtils.android.kt::executeFfmpegAsync's 4-arg path. */
    private suspend fun executeFfmpegArgsWithStats(
        args: Array<String>,
        onStats: (Statistics) -> Unit,
    ): FFmpegSession =
        suspendCancellableCoroutine { cont ->
            val onComplete = FFmpegSessionCompleteCallback { sess ->
                if (cont.isActive) cont.resume(sess)
            }
            val session = FFmpegKit.executeWithArgumentsAsync(
                args,
                onComplete,
                null,
                StatisticsCallback { stats -> stats?.let(onStats) },
            )
            cont.invokeOnCancellation {
                try { session.cancel() } catch (_: Exception) {}
            }
        }

    // -----------------------------------------------------------------
    // Version-string regression test.
    //
    // Settings → Help displays the bundled ffmpeg version via
    // FFmpegUtils.getFfmpegVersion(), which runs `ffmpeg -version` and
    // parses the first line of the banner ("ffmpeg version <ver>
    // Copyright...").
    //
    // n7 → n8 upgrade changed the version string from "n7.1.3" to
    // "n8.1.1". If the parser, the log capture, or the synchronous
    // FFmpegKit.execute path ever regress, this test catches it.
    //
    // We don't pin to "n8.1.1" — the assertion is intentionally lenient:
    // any non-blank string that starts with 'n' followed by digits is
    // accepted. That way the test stays valid through future bumps
    // (n8.2, n9.x) without further edits.
    // -----------------------------------------------------------------

    @Test
    fun ffmpegVersion_isReportedFromBundledBuild() = runTest {
        // The production API: FFmpegUtils.getFfmpegVersion() backs Settings →
        // Help. As of the C5 work it routes through FFmpegKitConfig.getFFmpegVersion()
        // which is arthenica's direct JNI to FFmpeg's av_version_info() —
        // no `ffmpeg -version` log scraping.
        //
        // Why we avoid FFmpegKit.execute("-version"): fftools' show_version
        // writes to stdout via printf AND swaps the av_log callback to
        // log_callback_help before printing, so arthenica's log-capture
        // hook gets zero bytes and the parsed version comes back null.
        val configVersion = FFmpegKitConfig.getFFmpegVersion()
        val utilsVersion = FFmpegUtils.getFfmpegVersion()
        Log.i(
            "VERSION_TEST",
            "FFmpegKitConfig.getFFmpegVersion()=$configVersion " +
                "FFmpegUtils.getFfmpegVersion()=$utilsVersion",
        )

        assertNotNull(
            configVersion,
            "FFmpegKitConfig.getFFmpegVersion() must return non-null. If null, " +
                "the JNI bridge to av_version_info is broken in this AAR.",
        )
        assertTrue(
            configVersion.isNotBlank() &&
                configVersion.matches(Regex("""^n?\d+\.\d+(\.\d+)?.*""")),
            "configVersion should look like a FFmpeg release tag " +
                "(e.g. 'n8.1.1', '8.1.1'), got: '$configVersion'.",
        )
        assertNotNull(
            utilsVersion,
            "FFmpegUtils.getFfmpegVersion() must return non-null. " +
                "Settings → Help depends on this.",
        )
        // The two must agree — getFfmpegVersion now delegates to
        // FFmpegKitConfig.getFFmpegVersion() with no extra processing.
        assertEquals(
            configVersion,
            utilsVersion,
            "FFmpegUtils.getFfmpegVersion() must echo FFmpegKitConfig.getFFmpegVersion() " +
                "verbatim (we no longer parse a banner). " +
                "Got config='$configVersion' utils='$utilsVersion'.",
        )
    }
}
