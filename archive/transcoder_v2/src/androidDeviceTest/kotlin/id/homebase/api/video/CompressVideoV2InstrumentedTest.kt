package id.homebase.api.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import id.homebase.api.ActivityProvider
import id.homebase.api.video.transcoder_v2.internal.AudioTrackFactory
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
 * v2-MediaCodec-specific instrumented tests, extracted from
 * `CompressVideoAndroidInstrumentedTest` when the v2 transcoder was
 * archived (see `archive/transcoder_v2/README.md`).
 *
 * **Dormant.** These tests aren't part of the compiled target while
 * `archive/` is outside any Gradle source set. When v2 is revived,
 * move this file back to
 * `homebase-api/src/androidDeviceTest/kotlin/id/homebase/api/video/`
 * alongside the production tests, and move the `test-fixtures/*.mp4`
 * back to `homebase-api/src/androidDeviceTest/assets/test_videos/`.
 *
 * Each test references v2 behaviour that the ffmpeg-kit production
 * path does not implement (continuous progress, PTS normalisation,
 * AAC pass-through, HDR tone-mapping), so they belong here, not in
 * the production test file.
 *
 * Fixtures needed (in `archive/transcoder_v2/test-fixtures/`):
 *  - `bbb_1080p_2mb.mp4`   (1080p 10s, video-only)
 *  - `bbb_1080p_10mb.mp4`  (1080p 10s, video-only, high bitrate)
 *  - `hdr10_720p.mp4`      (HEVC HDR10 / PQ, 720p)
 *
 * Plus `sample.mp4` from the production assets (still in
 * `homebase-api/src/androidDeviceTest/assets/test_videos/` — needed for
 * the AAC pass-through tests which require an AAC audio track; bbb
 * fixtures are video-only).
 */
@RunWith(AndroidJUnit4::class)
class CompressVideoV2InstrumentedTest {

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

    // ----------------------------------------------------------------------
    // Progress-fidelity (v2 emits continuous ticks; sync ffmpeg doesn't)
    // ----------------------------------------------------------------------

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

    // ----------------------------------------------------------------------
    // Fixture-coverage matrix — one test per scenario per fixture
    // ----------------------------------------------------------------------

    @Test
    fun compressVideo_mp4Recompress_bbb1080p2mb_producesPlayableOutput() = runTest {
        // 1080p input for 10s. STANDARD targets 720p / 2.5 Mbps, so the source
        // FAILS the already-optimal check on short-edge (1080 > 720) and we
        // must re-encode. We don't assert a strict size bound — MediaCodec's
        // encoder treats KEY_BIT_RATE as a request, not a hard cap.
        val fixture = copyFixtureToCache("bbb_1080p_2mb.mp4")
        val cleanup = mutableListOf<File>(fixture)
        try {
            val out = FFmpegUtils.compressVideo(
                inputPath = fixture.absolutePath,
                quality = VideoQuality.STANDARD,
            )
            assertNotNull(out, "1080p source above quality envelope must produce a recompressed file")
            cleanup += File(out)
            val outFile = File(out)
            assertTrue(outFile.length() > 0L, "compressed output must be non-empty")
            assertEquals("avc", detectCodec(out), "output must be H.264 (MIME suffix 'avc')")
            val durMs = FFmpegUtils.getDurationMs(out)
            assertTrue(durMs in 9_500L..10_500L, "expected ~10s duration, got ${durMs}ms")
        } finally {
            cleanup.forEach { it.delete() }
        }
    }

    @Test
    fun compressVideo_hlsRecompress_bbb1080p10mb_atHighCrossesFiveMbThreshold() = runTest {
        // At HIGH quality (5 Mbps + 192 kbps) for 10s the output lands ≥5 MB,
        // crossing the HLS threshold in VideoPayloadProcessor.
        val fixture = copyFixtureToCache("bbb_1080p_10mb.mp4")
        val cleanup = mutableListOf<File>(fixture)
        try {
            val out = FFmpegUtils.compressVideo(
                inputPath = fixture.absolutePath,
                quality = VideoQuality.HIGH,
            )
            assertNotNull(out, "1080p source at HIGH must produce a recompressed file")
            cleanup += File(out)
            val sizeBytes = File(out).length()
            assertTrue(
                sizeBytes >= 5L * 1024 * 1024,
                "HIGH-quality 10s output must cross 5 MB to route through HLS (got $sizeBytes bytes)",
            )
            assertEquals("avc", detectCodec(out), "output must be H.264 (MIME suffix 'avc')")
        } finally {
            cleanup.forEach { it.delete() }
        }
    }

    @Test
    fun compressVideo_hdr10_720p_producesTonemappedSdrOrControlledFailure() = runTest {
        // HDR10 / PQ HEVC input.
        //
        // Behaviour contract per SPEC §11 + §10 amendment:
        //   - Hardware HEVC decoder available: tone-map → SDR output.
        //   - Only software HEVC decoders (emulator): tone-map silently
        //     broken → HdrDecoderUnavailableException → null. Defensive.
        val fixture = copyFixtureToCache("hdr10_720p.mp4")
        val cleanup = mutableListOf<File>(fixture)
        try {
            val out = FFmpegUtils.compressVideo(
                inputPath = fixture.absolutePath,
                quality = VideoQuality.STANDARD,
            )

            if (hasHardwareHevcDecoder()) {
                assertNotNull(out, "HDR10 with hardware HEVC available must transcode successfully")
                cleanup += File(out)
                assertTrue(File(out).length() > 0L, "HDR transcode output must be non-empty")
                val transfer = readColorTransfer(out)
                if (transfer != null) {
                    assertTrue(
                        transfer != MediaFormat.COLOR_TRANSFER_ST2084 &&
                            transfer != MediaFormat.COLOR_TRANSFER_HLG,
                        "output transfer must be SDR (got ${transferName(transfer)})",
                    )
                }
            } else {
                assertEquals(
                    null,
                    out,
                    "no hardware HEVC decoder → expected HdrDecoderUnavailableException → null",
                )
            }
        } finally {
            cleanup.forEach { it.delete() }
        }
    }

    // ----------------------------------------------------------------------
    // Trim PTS normalization + AAC pass-through
    // ----------------------------------------------------------------------

    @Test
    fun compressVideo_trimNormalizesFirstSamplePtsToZero() = runTest {
        val fixture = copyFixtureToCache("bbb_1080p_2mb.mp4")
        val cleanup = mutableListOf<File>(fixture)
        try {
            val out = FFmpegUtils.compressVideo(
                inputPath = fixture.absolutePath,
                trimStartMs = 2_000L,
                trimEndMs = 5_000L,
                quality = VideoQuality.STANDARD,
            )
            assertNotNull(out, "trim must produce output")
            cleanup += File(out)

            val firstSamplePts = readFirstNonConfigSamplePts(out, "video/")
            assertNotNull(firstSamplePts, "output must have at least one video sample")
            assertTrue(
                firstSamplePts in 0L..100_000L,
                "first video sample PTS must be normalized to ~0 (got ${firstSamplePts}µs); " +
                    "pre-normalization this was ≈ 2_000_000µs",
            )

            val firstAudioPts = readFirstNonConfigSamplePts(out, "audio/")
            if (firstAudioPts != null) {
                assertTrue(
                    firstAudioPts in 0L..100_000L,
                    "first audio sample PTS must be normalized to ~0 (got ${firstAudioPts}µs)",
                )
            }
        } finally {
            cleanup.forEach { it.delete() }
        }
    }

    @Test
    fun compressVideo_aacInput_skipsCodecConstruction() = runTest {
        // sample.mp4 has AAC-LC ≤128 kbps audio. With a trim (forces real
        // transcode), AudioTrackFactory should pick AudioRemuxer (pass-through)
        // → zero audio codecs constructed. Without pass-through → 2.
        val fixture = copyFixtureToCache("sample.mp4")
        val cleanup = mutableListOf<File>(fixture)
        try {
            val out = FFmpegUtils.compressVideo(
                inputPath = fixture.absolutePath,
                trimStartMs = 0L,
                trimEndMs = 5_000L,
                quality = VideoQuality.STANDARD,
            )
            assertNotNull(out, "compress must succeed")
            cleanup += File(out)

            assertEquals(
                0,
                AudioTrackFactory.lastCodecConstructionCount,
                "AAC-LC input must take pass-through path (no audio codec construction)",
            )
        } finally {
            cleanup.forEach { it.delete() }
        }
    }

    @Test
    fun compressVideo_aacInput_preservesAudioSampleSequence() = runTest {
        // Pass-through must echo input audio samples bit-identical to output
        // (modulo trim cutting off later samples).
        val fixture = copyFixtureToCache("sample.mp4")
        val cleanup = mutableListOf<File>(fixture)
        try {
            val out = FFmpegUtils.compressVideo(
                inputPath = fixture.absolutePath,
                trimStartMs = 0L,
                trimEndMs = 5_000L,
                quality = VideoQuality.STANDARD,
            )
            assertNotNull(out, "compress must succeed")
            cleanup += File(out)

            val inputSizes = readAudioSampleSizes(fixture.absolutePath)
            val outputSizes = readAudioSampleSizes(out)

            assertTrue(inputSizes.isNotEmpty(), "fixture must have audio samples")
            assertTrue(outputSizes.isNotEmpty(), "output must have audio samples")
            assertTrue(
                outputSizes.size <= inputSizes.size,
                "output count (${outputSizes.size}) cannot exceed input count (${inputSizes.size}) under trim",
            )
            assertEquals(
                inputSizes.take(outputSizes.size),
                outputSizes,
                "pass-through must preserve every audio sample size byte-for-byte",
            )
        } finally {
            cleanup.forEach { it.delete() }
        }
    }

    // ----------------------------------------------------------------------
    // Test helpers
    // ----------------------------------------------------------------------

    /** Returns the video track's MIME suffix (e.g. "h264", "hevc") or null. */
    private fun detectCodec(path: String): String? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) return mime.substringAfter("video/")
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /** Reads `KEY_COLOR_TRANSFER` from the output's video track, or null if absent. */
    private fun readColorTransfer(path: String): Int? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("video/")) continue
                if (fmt.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
                    return fmt.getInteger(MediaFormat.KEY_COLOR_TRANSFER)
                }
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /**
     * True iff the device exposes at least one HEVC decoder that is a true
     * hardware-accelerated codec. Hardware decoders are required for the
     * `KEY_COLOR_TRANSFER_REQUEST` tone-mapping path to actually work.
     */
    private fun hasHardwareHevcDecoder(): Boolean {
        val infos = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        for (info: MediaCodecInfo in infos) {
            if (info.isEncoder) continue
            val supportsHevc = info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) }
            if (!supportsHevc) continue
            val lower = info.name.lowercase()
            val nameLooksSoftware = lower.startsWith("omx.google.") ||
                lower.startsWith("c2.android.") ||
                "swiftshader" in lower ||
                // Android emulator's host-backed codecs. They report as
                // hardware-accelerated but don't perform real HDR tone-mapping.
                "goldfish" in lower
            if (nameLooksSoftware) continue
            if (Build.VERSION.SDK_INT >= 29) {
                if (info.isSoftwareOnly) continue
                if (!info.isHardwareAccelerated) continue
            }
            return true
        }
        return false
    }

    /** PTS of the first non-config sample in the matching track; null if absent. */
    private fun readFirstNonConfigSamplePts(path: String, mimePrefix: String): Long? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            var trackIdx = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith(mimePrefix)) {
                    trackIdx = i
                    break
                }
            }
            if (trackIdx < 0) return null
            extractor.selectTrack(trackIdx)
            while (true) {
                val pts = extractor.sampleTime
                if (pts < 0) return null
                if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) == 0) {
                    return pts
                }
                extractor.advance()
            }
            @Suppress("UNREACHABLE_CODE") null
        } catch (_: Exception) {
            null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    /**
     * Byte sizes of every payload audio sample in [path] with PTS ≥
     * [trimStartUs]. Skips codec-config + partial-frame samples (redundant
     * with the track's MediaFormat csd-0/csd-1 keys; dropped by MediaMuxer)
     * and AAC pre-roll samples with negative PTS that AudioRemuxer skips
     * on the pass-through path.
     */
    private fun readAudioSampleSizes(path: String, trimStartUs: Long = 0L): List<Int> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(path)
            var trackIdx = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIdx = i
                    break
                }
            }
            if (trackIdx < 0) return emptyList()
            extractor.selectTrack(trackIdx)
            val sizes = ArrayList<Int>()
            val buf = java.nio.ByteBuffer.allocateDirect(16 * 1024)
            val skipFlags = MediaCodec.BUFFER_FLAG_CODEC_CONFIG or MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME
            while (true) {
                buf.clear()
                val size = extractor.readSampleData(buf, 0)
                if (size < 0) break
                val isConfig = (extractor.sampleFlags and skipFlags) != 0
                val isPreTrim = extractor.sampleTime < trimStartUs
                if (!isConfig && !isPreTrim) {
                    sizes.add(size)
                }
                extractor.advance()
            }
            sizes
        } catch (_: Exception) {
            emptyList()
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    private fun transferName(transfer: Int): String = when (transfer) {
        MediaFormat.COLOR_TRANSFER_LINEAR -> "LINEAR"
        MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "SDR_VIDEO"
        MediaFormat.COLOR_TRANSFER_ST2084 -> "ST2084 (PQ, HDR)"
        MediaFormat.COLOR_TRANSFER_HLG -> "HLG (HDR)"
        else -> "unknown($transfer)"
    }
}
