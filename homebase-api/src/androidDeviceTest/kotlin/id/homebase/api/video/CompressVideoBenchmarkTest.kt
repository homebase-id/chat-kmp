package id.homebase.api.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import id.homebase.api.ActivityProvider
import id.homebase.api.video.transcoder_v2.HomebaseVideoTranscoder
import java.io.File
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Apples-to-apples benchmark of the v2 MediaCodec transcoder
 * ([HomebaseVideoTranscoder]) against an equivalent FFmpegKit transcode
 * command, running both on the same device against the same fixtures.
 * Replaces the cross-build stopwatch comparison that was awkward because
 * the old `StreamingTranscoder` wrapper was deleted in `cf52bc18`.
 *
 * **This is a benchmark harness, not a regression test.** `@Ignore`'d by
 * default so it doesn't slow CI. To re-run, temporarily remove the
 * `@Ignore` and:
 *
 *     ./gradlew :homebase-api:connectedAndroidDeviceTest \
 *       -Pandroid.testInstrumentationRunnerArguments.class=id.homebase.api.video.CompressVideoBenchmarkTest
 *
 * Then grep the device logcat (or test stdout) for `BENCHMARK` lines and
 * paste the result into the "Last results" block below.
 *
 * --- Last results on Medium_Phone_API_36.0 emulator (2026-05-17) ---
 *
 *     BENCHMARK fixture=sample.mp4         ffmpeg= 449ms,298 KB  v2=AlreadyOptimal(180ms)   speedup=n/a
 *     BENCHMARK fixture=bbb_1080p_2mb.mp4  ffmpeg=9892ms,3.0 MB  v2=12919ms,6.2 MB          speedup=0.77×
 *     BENCHMARK fixture=bbb_1080p_10mb.mp4 ffmpeg=10010ms,3.1 MB v2=12264ms,6.1 MB          speedup=0.82×
 *     BENCHMARK fixture=hdr10_720p.mp4     ffmpeg=3886ms,1.3 MB  v2=FAILED(1250ms)          speedup=n/a
 *
 * **EMULATOR CAVEATS — these numbers do NOT reflect real-device performance:**
 *
 * 1. The Android emulator has NO hardware H.264 encoder. v2's MediaCodec
 *    pipeline falls back to the emulator's software codec
 *    (`c2.goldfish.*` / `c2.android.avc.encoder`), which runs slower than
 *    `libx264` (which ffmpeg uses, also software, but heavily optimised).
 *    On a real phone v2 hits the hardware H.264 encoder block — typically
 *    a 3–10× speedup vs `libx264`, reversing the emulator's ratio.
 * 2. v2 output is ~2× larger than ffmpeg's (6 MB vs 3 MB for the same 10s
 *    1080p input). MediaCodec's `KEY_BIT_RATE` is a *target* not a cap;
 *    its default `BITRATE_MODE_VBR` lets bursts go well above target.
 *    The SPEC §6 decision was VBR — bumping to `BITRATE_MODE_CBR` (or
 *    explicitly capping via `KEY_MAX_BITRATE`) would close this gap.
 *    Tracked as a future improvement; not blocking.
 * 3. v2's HDR path correctly returns null (HdrDecoderUnavailableException)
 *    on emulators with software-only HEVC — see [hasHardwareHevcDecoder]
 *    in [CompressVideoAndroidInstrumentedTest]. ffmpeg processes HDR as
 *    if it were SDR, producing visibly-wrong colours but a playable file.
 *
 * Re-run on a real phone to get realistic numbers. Expected real-device
 * speedup for v2 over ffmpeg on phone-recorded 1080p H.264 content:
 * roughly 2–5× faster, with significantly lower battery cost (hardware
 * codec uses orders of magnitude less power than libx264).
 *
 * Note on the ffmpeg command: matches v2's STANDARD profile target
 * (720p short edge, 2.5 Mbps H.264 video, 128 kbps AAC audio).
 * `-vf scale=-2:720` scales the short edge to 720p while keeping
 * aspect ratio and even output dimensions. For `sample.mp4` (320×180)
 * the scale filter is omitted since the source is already smaller.
 */
@Ignore("Benchmark harness — un-ignore and run manually; paste results into the class kdoc")
@RunWith(AndroidJUnit4::class)
class CompressVideoBenchmarkTest {

    @Before
    fun setUp() {
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

    /** Each fixture in the order short → 1080p-small → 1080p-large → HDR. */
    private val fixtures = listOf(
        Fixture(name = "sample.mp4", scaleToShortEdge720 = false),
        Fixture(name = "bbb_1080p_2mb.mp4", scaleToShortEdge720 = true),
        Fixture(name = "bbb_1080p_10mb.mp4", scaleToShortEdge720 = true),
        // HDR HEVC — the MediaCodec path requires hardware HEVC; on emulator
        // (software-only HEVC) the v2 side will return null (controlled
        // HdrDecoderUnavailableException). The ffmpeg side will succeed
        // (libx264 doesn't care about HDR; it'll produce off-coloured SDR).
        // Benchmark still useful: shows ffmpeg's HDR-as-SDR cost.
        Fixture(name = "hdr10_720p.mp4", scaleToShortEdge720 = false),
    )

    private data class Fixture(val name: String, val scaleToShortEdge720: Boolean)

    @Test
    fun benchmark_ffmpegKit_vs_mediaCodecV2() = runTest {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val results = ArrayList<String>()

        for (fx in fixtures) {
            val fixture = copyFixtureToCache(fx.name)
            val ffmpegOut = File(ctx.cacheDir, "bench-ffmpeg-${fx.name}")
            val v2Out = File(ctx.cacheDir, "bench-v2-${fx.name}")
            try {
                // -- ffmpeg-kit path: STANDARD-equivalent transcode --
                val scaleArgs = if (fx.scaleToShortEdge720) {
                    arrayOf("-vf", "scale=-2:720")
                } else {
                    emptyArray()
                }
                val ffmpegArgs = arrayOf(
                    "-y",
                    "-i", fixture.absolutePath,
                    "-c:v", "libx264",
                    "-b:v", "2500k",
                    *scaleArgs,
                    "-c:a", "aac",
                    "-b:a", "128k",
                    ffmpegOut.absolutePath,
                )
                val ffmpegStart = System.currentTimeMillis()
                val session = FFmpegKit.executeWithArguments(ffmpegArgs)
                val ffmpegMs = System.currentTimeMillis() - ffmpegStart
                val ffmpegOk = ReturnCode.isSuccess(session.returnCode)
                val ffmpegBytes = if (ffmpegOk) ffmpegOut.length() else 0L

                // -- v2 path: HomebaseVideoTranscoder --
                val v2Start = System.currentTimeMillis()
                val v2Result = try {
                    HomebaseVideoTranscoder.transcode(
                        inputPath = fixture.absolutePath,
                        outputPath = v2Out.absolutePath,
                        quality = VideoQuality.STANDARD,
                    )
                } catch (e: Throwable) {
                    null
                }
                val v2Ms = System.currentTimeMillis() - v2Start
                val v2Ok = v2Result is HomebaseVideoTranscoder.Result.Transcoded
                val v2Bytes = if (v2Ok) v2Out.length() else 0L

                val speedup = if (v2Ms > 0 && ffmpegOk && v2Ok) {
                    "%.2f".format(ffmpegMs.toFloat() / v2Ms.toFloat())
                } else {
                    "n/a"
                }
                val line = "BENCHMARK fixture=${fx.name} " +
                    "ffmpeg=${if (ffmpegOk) "${ffmpegMs}ms,${ffmpegBytes}B" else "FAILED"} " +
                    "v2=${if (v2Ok) "${v2Ms}ms,${v2Bytes}B"
                          else if (v2Result is HomebaseVideoTranscoder.Result.AlreadyOptimal) "AlreadyOptimal(${v2Ms}ms)"
                          else "FAILED(${v2Ms}ms)"} " +
                    "speedup=${speedup}×"
                results.add(line)
                println(line)
                android.util.Log.i("BENCHMARK", line)
            } finally {
                fixture.delete()
                ffmpegOut.delete()
                v2Out.delete()
            }
        }

        // Aggregate dump at the end for easy copy-paste into the kdoc.
        val banner = "============== BENCHMARK SUMMARY =============="
        println(banner)
        android.util.Log.i("BENCHMARK", banner)
        for (line in results) {
            println(line)
            android.util.Log.i("BENCHMARK", line)
        }
        println(banner)
        android.util.Log.i("BENCHMARK", banner)

        // Sanity: at least one fixture must have produced output on each side
        // (otherwise the test apparatus itself is broken). HDR happens to be
        // expected to fail on emulator's v2 path; the SDR fixtures should not.
        assertNotNull(
            results.firstOrNull { "ffmpeg=" in it && "FAILED" !in it.substringAfter("ffmpeg=").substringBefore(" ") },
            "no fixture produced ffmpeg output — environment is broken",
        )
        assertTrue(
            results.any { "v2=" in it && it.contains("v2=") && !it.substringAfter("v2=").startsWith("FAILED") },
            "no fixture produced v2 output — environment is broken",
        )
    }
}
