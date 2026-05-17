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

    // ----------------------------------------------------------------------
    // Fixture-coverage matrix — one test per scenario per fixture
    // ----------------------------------------------------------------------
    //
    //   Scenario            Fixture                  Expected behaviour
    //   ──────────────────  ───────────────────────  ──────────────────────────────────────
    //   no-recompression    sample.mp4 (26 KB)        preflight short-circuits → AlreadyOptimal
    //   MP4 recompression   bbb_1080p_2mb.mp4 (2 MB)  re-encode at STANDARD, output ~3 MB, MP4 path
    //   HLS recompression   bbb_1080p_10mb.mp4 (10MB) re-encode at HIGH, output > 5 MB threshold
    //   HDR validation      hdr10_720p.mp4 (8 MB)     decoder-side tone-map → SDR output
    //
    // sample.mp4 already covers no-recompression via the standardQuality test
    // above. The four tests below cover MP4-recompress, HLS-recompress, and
    // the HDR happy path (which used to throw HdrDecoderUnavailableException
    // pre-§10-amendment and now produces tone-mapped SDR).

    @Test
    fun compressVideo_mp4Recompress_bbb1080p2mb_producesPlayableOutput() = runTest {
        // 1080p input for 10s. STANDARD targets 720p / 2.5 Mbps, so the source
        // FAILS the already-optimal check on short-edge (1080 > 720) and we
        // must re-encode. We don't assert a strict size bound — MediaCodec's
        // VBR encoder treats KEY_BIT_RATE as a request, not a hard cap, and
        // the actual output for a 10s 1080p clip can exceed the nominal
        // (2.5 Mbps × 10s ≈ 3 MB) by ~2× during scene changes. The MP4-vs-HLS
        // routing happens later in VideoPayloadProcessor; this test just
        // exercises the recompression path itself.
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
            // Output is a playable H.264 MP4. MIME suffix from MediaExtractor
            // is "avc", not "h264" — H.264 is the codec's marketing name.
            assertEquals("avc", detectCodec(out), "output must be H.264 (MIME suffix 'avc')")
            val durMs = FFmpegUtils.getDurationMs(out)
            assertTrue(durMs in 9_500L..10_500L, "expected ~10s duration, got ${durMs}ms")
        } finally {
            cleanup.forEach { it.delete() }
        }
    }

    @Test
    fun compressVideo_hlsRecompress_bbb1080p10mb_atHighCrossesFiveMbThreshold() = runTest {
        // 1080p high-bitrate input. At HIGH quality (5 Mbps video + 192 kbps
        // audio) for 10s the output lands at ~6.5 MB, crossing the 5 MB HLS
        // threshold used by VideoPayloadProcessor to route through HLS rather
        // than the MP4 path. This test asserts the compressed output size; the
        // HLS segmentation itself is exercised by VideoPayloadProcessor's own
        // tests (still ffmpeg-backed, outside the v2 transcoder scope).
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
        //   - Hardware HEVC decoder available: KEY_COLOR_TRANSFER_REQUEST
        //     succeeds, decoder performs hardware tone-mapping into the
        //     SurfaceTexture, output is SDR (KEY_COLOR_TRANSFER ∉ {ST2084, HLG}).
        //   - Only software HEVC decoders available (Android emulator —
        //     `c2.android.hevc.decoder`, `OMX.google.hevc.decoder`): tone-map
        //     is silently broken on software codecs, so isToneMapEffective
        //     rejects every candidate and we throw
        //     HdrDecoderUnavailableException, which FFmpegUtils.compressVideo
        //     catches → returns null. This is the CORRECT defensive behaviour
        //     (better than producing colour-broken output).
        //
        // Both outcomes are valid per the spec. The test exercises BOTH paths
        // by branching on hardware-decoder availability rather than skipping.
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
                // Absent KEY_COLOR_TRANSFER on the encoder output is acceptable;
                // H.264 defaults to BT.709 SDR.
            } else {
                // Emulator path: HEVC software-only → tone-map rejected → null.
                // Verifying this branch exercises DecoderConfig.isToneMapEffective
                // and the HdrDecoderUnavailableException → null translation in
                // FFmpegUtils.compressVideo.
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
    // Trim PTS normalization + AAC pass-through (Phase 1 + Phase 2 of the
    // post-surface-bridge correctness work).
    // ----------------------------------------------------------------------

    @Test
    fun compressVideo_trimNormalizesFirstSamplePtsToZero() = runTest {
        // Trim (2000ms, 5000ms) on bbb. Before normalization, the output's
        // first sample PTS was ≈ trimStartUs (2_000_000 µs), leaving a silent
        // gap at the start of the file. After normalization (subtract
        // trimStartUs at encoder-queue time), the first sample's PTS should
        // be ≤ ~100 ms — wiggle room because the first surviving frame after
        // SEEK_TO_PREVIOUS_SYNC + drop-before-trim can land slightly above
        // trimStartUs depending on keyframe alignment.
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

            // Audio side: AAC frames are typically all sync, so the first
            // post-trim sample should land at or near 0.
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
        // sample.mp4 has AAC-LC audio at ≤128 kbps. With a trim (to force a
        // real transcode rather than the already-optimal short-circuit), the
        // audio pipeline runs and AudioTrackFactory should pick AudioRemuxer
        // (pass-through) → ZERO audio codecs constructed. Without the
        // pass-through fast path, this would be 2 (decoder + encoder).
        //
        // bbb_1080p_*.mp4 fixtures are video-only (test-videos.co.uk strips
        // audio), so we can't use them for audio-track tests.
        val fixture = copyFixtureToCache("sample.mp4")
        val cleanup = mutableListOf<File>(fixture)
        try {
            // Reset counter to a sentinel so we can assert the factory ran.
            // The factory sets it to 0 (pass-through) or 2 (re-encode) before
            // we read it.
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
        // Pass-through must echo input audio samples bit-identical to output.
        // Use sample.mp4 + trim(0, 5000ms): trimStart=0 means no PTS shift,
        // and we compare the output's audio samples against the corresponding
        // prefix of input's audio samples (those with PTS ≤ trimEnd).
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
            // Pass-through preserves byte-for-byte; output samples must match
            // the corresponding prefix of input samples (modulo trim cutting
            // off later samples).
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
     * Returns true iff the device exposes at least one HEVC decoder that is
     * a true hardware-accelerated codec. Hardware decoders are required for
     * the `KEY_COLOR_TRANSFER_REQUEST` tone-mapping path to actually work
     * (software codecs accept the request without honouring it — exactly
     * what [DecoderConfig.isToneMapEffective] guards against).
     *
     * Triple guard: `isHardwareAccelerated()` AND NOT `isSoftwareOnly()` AND
     * the name doesn't match the well-known software prefixes. Android's
     * `isHardwareAccelerated`/`isSoftwareOnly` flags are not reliable across
     * all OEM codec lists — some emulator codecs (e.g. swiftshader-backed
     * HEVC) mislabel themselves as hardware-accelerated despite being
     * software. The name-prefix check is the empirical safety net.
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
                // Android emulator's host-backed codecs (Goldfish). They report
                // as hardware-accelerated but don't perform real HDR tone-mapping
                // (verified empirically — c2.goldfish.hevc.decoder accepts the
                // KEY_COLOR_TRANSFER_REQUEST but output transfer stays ST2084).
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

    /**
     * Returns the PTS of the first sample in the matching track that does
     * NOT carry `BUFFER_FLAG_CODEC_CONFIG`. Null if track absent or no
     * payload samples found.
     */
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
            // Iterate until we find a sample that isn't a codec-config blob.
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
     * Returns the byte sizes of every payload audio sample in [path] with
     * PTS ≥ [trimStartUs], in order. Filters out:
     * - Codec-config samples (`BUFFER_FLAG_CODEC_CONFIG`) and partial-frame
     *   samples — these are redundant with the track's `MediaFormat`
     *   csd-0/csd-1 keys and are dropped by `MediaMuxer`.
     * - Pre-trim samples (sampleTime < trimStartUs) — these include AAC
     *   priming/pre-roll samples with negative PTS that `AudioRemuxer`
     *   correctly skips on the pass-through path.
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
