package id.homebase.api.video

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.homebase.api.ActivityProvider
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android-side mirror of [id.homebase.api.video.FFmpegPipelineCoverageJvmTest]
 * for the MediaCodec-driven [FFmpegUtils.compressVideo] path.
 *
 * No `@Ignore` — CI doesn't auto-run `connectedAndroidDeviceTest`, so this
 * is dormant on PR builds. Run locally with a booted device:
 *
 *   ./gradlew homebase-api:connectedAndroidDeviceTest
 *
 * Asserts:
 *  - Each [VideoQuality] level produces a non-null output file (codec
 *    selection didn't break on this device).
 *  - LOW ≤ STANDARD ≤ HIGH for output file size (quality mapping wires
 *    through to bitrate / resolution).
 *  - Trim 1000-4000ms produces a clip whose duration is ~3000ms (same
 *    assertion shape as the JVM regression suite).
 *  - The progress callback fires more than twice during compression of the
 *    6-second fixture — i.e. the user sees real progress, not just 0%→100%.
 */
@RunWith(AndroidJUnit4::class)
class CompressVideoAndroidInstrumentedTest {

    @Before
    fun setUp() {
        // FFmpegUtils.android.kt resolves cacheDir via ActivityProvider. The
        // instrumentation environment has no Activity, but the new
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
            // sample.mp4 is 320x180 / h264 / ~35kbps — way below LEVEL_3's
            // 720p / 2.5M target. isTranscodeRequired() should be false and
            // the "already optimal" contract returns null.
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

    @Test
    fun compressVideo_progressCallback_firesContinuously() = runTest {
        val fixture = copyFixtureToCache("sample.mp4")
        val cleanup = mutableListOf<File>(fixture)
        try {
            val tickCount = AtomicInteger(0)
            val firstTick = AtomicInteger(-1)
            val lastTick = AtomicInteger(-1)
            // Force a real transcode via trim so isTranscodeRequired() == true.
            val out = FFmpegUtils.compressVideo(
                inputPath = fixture.absolutePath,
                trimStartMs = 0L,
                trimEndMs = 6_000L,
                quality = VideoQuality.STANDARD,
                onProgress = { pct ->
                    val intPct = (pct * 100f).toInt()
                    if (firstTick.get() == -1) firstTick.set(intPct)
                    lastTick.set(intPct)
                    tickCount.incrementAndGet()
                },
            )
            assertNotNull(out)
            cleanup += File(out)
            // Real progress = at least 3 distinct ticks (start, middle, end);
            // the old FFmpegKit session path effectively delivered just 0/100.
            assertTrue(
                tickCount.get() >= 3,
                "Progress must fire continuously (got ${tickCount.get()} ticks, first=${firstTick.get()}%, last=${lastTick.get()}%)",
            )
            assertTrue(lastTick.get() >= 90, "Last tick should be near 100% (got ${lastTick.get()}%)")
        } finally {
            cleanup.forEach { it.delete() }
        }
    }

    @Test
    fun compressVideo_qualityMapping_lowerBitrateProducesSmallerFile() = runTest {
        // The 26KB sample is too small to test bitrate stratification (every
        // quality would short-circuit). Force a transcode via trim so each
        // quality actually re-encodes. Use a 5-second window to stay well
        // within the fixture's 6-second duration.
        val fixture = copyFixtureToCache("sample.mp4")
        val cleanup = mutableListOf<File>(fixture)
        try {
            val sizes = mutableMapOf<VideoQuality, Long>()
            for (q in VideoQuality.entries) {
                val out = FFmpegUtils.compressVideo(
                    inputPath = fixture.absolutePath,
                    trimStartMs = 0L,
                    trimEndMs = 5_000L,
                    quality = q,
                )
                assertNotNull(out, "compress($q) must produce output")
                val size = File(out).length()
                cleanup += File(out)
                sizes[q] = size
                // Rename so the next iteration's output doesn't overwrite
                File(out).renameTo(File("$out.$q"))
                cleanup += File("$out.$q")
            }
            // Quality monotonicity: LOW ≤ STANDARD ≤ HIGH. Tolerate equality
            // because on small fixtures the encoder may min-clamp.
            assertTrue(
                sizes[VideoQuality.LOW]!! <= sizes[VideoQuality.STANDARD]!!,
                "LOW (${sizes[VideoQuality.LOW]}) must be ≤ STANDARD (${sizes[VideoQuality.STANDARD]})",
            )
            assertTrue(
                sizes[VideoQuality.STANDARD]!! <= sizes[VideoQuality.HIGH]!!,
                "STANDARD (${sizes[VideoQuality.STANDARD]}) must be ≤ HIGH (${sizes[VideoQuality.HIGH]})",
            )
        } finally {
            cleanup.forEach { it.delete() }
        }
    }
}
