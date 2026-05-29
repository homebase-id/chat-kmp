package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Cross-platform regression test for the video **compression seam**, exercised end to end
 * through the platform's *real* ffmpeg infrastructure — the same generic seam production
 * code calls.
 *
 * [VideoCompressionService] is the consumer-facing entry point; it delegates to
 * [FFmpegVideoCompressor], which in turn calls the per-platform [FFmpegUtils] actual.
 * Driving the tests through the service (not `FFmpegUtils` directly) means they also cover
 * the delegation + the `Pair → `[SegmentedVideo]` mapping that production now depends on —
 * a swapped field or broken delegation surfaces here against real ffmpeg, which the
 * fake-based `VideoPayloadProcessorCompressionSeamTest` cannot catch.
 *
 * **Three concerns × two containers.** Each concern below runs against BOTH the `.mp4`
 * fixture ([SampleVideoFixture]) and the QuickTime `.mov` fixture ([SampleMovFixture]) so the
 * suite covers the `.mov` ingest path (iOS camera captures are `.mov`) on every platform:
 *  1. `compress_*` — compression re-encodes and returns an output path.
 *  2. `segment_*` — HLS segmentation (plain + encrypted) maps the [SegmentedVideo] fields.
 *  3. `progress_*` — the progress (performance-counter) callback fires for compress + segment.
 *
 * This is the surface that would have caught the iOS bridge crash this PR initially shipped:
 * the previous gap was that every video test exercised the thumbnail extractor or talked to
 * FFmpegKit directly, but nothing went through the compression entry point that
 * `MessageAttachmentBuilder` / `MomentsPostSenderService` use for every uploaded video.
 *
 * **Per-platform routing — reuses the same staging seam as [FfmpegDecoderCommonTest]:**
 *
 * | Target                     | What this test exercises                                                                 |
 * |----------------------------|------------------------------------------------------------------------------------------|
 * | iOS sim (nativeTest)       | ✅ Real ffmpeg via `TestFFmpegKitBridge` (Kotlin/Native cinterop → FFmpegKit).            |
 * | Web (wasmJsTest)           | ✅ Real ffmpeg.wasm via `globalThis.__odinFfmpeg`.                                        |
 * | JVM (homebase-api jvmTest) | ⚠️ Silent skip — no ffmpeg binaries on this classpath. JVM coverage lives in              |
 * |                            |    `FFmpegPipelineCoverageJvmTest` (homebase-chat:jvmTest), which calls `FFmpegUtils`.    |
 * | Android host               | ⚠️ Silent skip — no ffmpeg infra in androidHostTest classpath.                           |
 * | Android device             | ⏸️ FFmpegKit AAR present but `CompressVideoAndroidInstrumentedTest` is `@Ignore`'d.       |
 *
 * Each test self-skips (`return@runTest`) where the stager returns null, and the segmentation
 * assertions are guarded on a non-null result so a platform that doesn't implement an op
 * (web returns null from `segment*`) is a no-op rather than a failure.
 *
 * **iOS Swift-bridge caveat.** This routes through `TestFFmpegKitBridge` (Kotlin/Native
 * cinterop), not the production `FFmpegKitBridgeImpl.swift`. Only
 * `iosApp/iosAppTests/FFmpegKitBridgeImplTests.swift` exercises the Swift path.
 */
class FFmpegCompressionCommonTest {

    // ffmpeg on a ~2-3 s clip is sub-second on iOS sim, ~5-15 s on the single-threaded
    // ffmpeg.wasm core. Bump the dispatcher timeout so the wasm leg doesn't false-timeout.
    private val opTimeout = kotlin.time.Duration.parse("60s")

    // ── compress ──────────────────────────────────────────────────────────────────────

    @Test
    fun compress_mp4_producesOutputPath() = runTest(timeout = opTimeout) {
        runCompress(stageSampleVideoForFfmpegTest())
    }

    @Test
    fun compress_mov_producesOutputPath() = runTest(timeout = opTimeout) {
        runCompress(stageSampleMovForFfmpegTest())
    }

    // ── segment (SegmentedVideo field mapping) ──────────────────────────────────────────

    @Test
    fun segment_mp4_mapsSegmentedVideoFields() = runTest(timeout = opTimeout) {
        runSegmentMapping(stageSampleVideoForFfmpegTest())
    }

    @Test
    fun segment_mov_mapsSegmentedVideoFields() = runTest(timeout = opTimeout) {
        runSegmentMapping(stageSampleMovForFfmpegTest())
    }

    // ── progress callbacks ──────────────────────────────────────────────────────────────

    @Test
    fun progress_mp4_firesDuringCompressAndSegment() = runTest(timeout = opTimeout) {
        runProgress(stageSampleVideoForFfmpegTest())
    }

    @Test
    fun progress_mov_firesDuringCompressAndSegment() = runTest(timeout = opTimeout) {
        runProgress(stageSampleMovForFfmpegTest())
    }

    // ── shared bodies ───────────────────────────────────────────────────────────────────

    /** Trim forces a re-encode (skips the planner's already-optimal short-circuit), so a
     *  non-null return proves the full seam → ffmpeg → output-file pipeline ran. */
    private suspend fun runCompress(input: String?) {
        if (input == null) return
        var output: String? = null
        try {
            output = VideoCompressionService.compress(
                inputPath = input,
                onProgress = null,
                trimStartMs = 0L,
                trimEndMs = 1_500L,
                quality = VideoQuality.STANDARD,
            )
            assertNotNull(output, "compress returned null — compression pipeline broken")
        } finally {
            cleanupStagedSampleVideo(input)
            output?.let { cleanupStagedSampleVideo(it) }
        }
    }

    /** Proves the `Pair → `[SegmentedVideo]` mapping in [FFmpegVideoCompressor] holds against
     *  real ffmpeg — a field swap flips the cross-platform string checks below. Null-tolerant:
     *  web's `segment*` returns null by design. */
    private suspend fun runSegmentMapping(input: String?) {
        if (input == null) return
        val produced = mutableListOf<String>()
        try {
            VideoCompressionService.segment(input, onProgress = null)?.let { seg ->
                produced += seg.playlistPath
                produced += seg.segmentsPath
                assertSegmentedVideoMapping("segment", seg)
            }
            VideoCompressionService.segmentAndEncrypt(
                inputPath = input,
                keyHeader = KeyHeader.newRandom16(),
                onProgress = null,
            )?.let { enc ->
                produced += enc.playlistPath
                produced += enc.segmentsPath
                assertSegmentedVideoMapping("segmentAndEncrypt", enc)
            }
        } finally {
            cleanupStagedSampleVideo(input)
            for (p in produced) cleanupStagedSampleVideo(p)
        }
    }

    /** Verifies the progress callback fires for compress and (where implemented) segment.
     *  [FFmpegVideoCompressor] guarantees a terminal `1f` on success, so this holds on every
     *  platform even where the underlying ffmpeg backend's mid-run ticks are best-effort
     *  (FFmpegKit emits zero statistics callbacks for a sub-second encode). Locks down that
     *  the `onProgress` wiring is intact end-to-end so a refactor dropping it is caught. */
    private suspend fun runProgress(input: String?) {
        if (input == null) return
        val produced = mutableListOf<String>()
        try {
            val compressProgress = mutableListOf<Float>()
            val out = VideoCompressionService.compress(
                inputPath = input,
                onProgress = { compressProgress.add(it) },
                trimStartMs = 0L,
                trimEndMs = 1_500L,
                quality = VideoQuality.STANDARD,
            )
            if (out != null) {
                produced += out
                assertProgressReported("compress", compressProgress)
            }

            val segProgress = mutableListOf<Float>()
            VideoCompressionService.segmentAndEncrypt(
                inputPath = input,
                keyHeader = KeyHeader.newRandom16(),
                onProgress = { segProgress.add(it) },
            )?.let { seg ->
                produced += seg.playlistPath
                produced += seg.segmentsPath
                assertProgressReported("segmentAndEncrypt", segProgress)
            }
        } finally {
            cleanupStagedSampleVideo(input)
            for (p in produced) cleanupStagedSampleVideo(p)
        }
    }

    // ── assertions ──────────────────────────────────────────────────────────────────────

    /** Asserts the [SegmentedVideo] fields aren't swapped: playlist is the `.m3u8`, segments
     *  path is distinct and isn't itself an `.m3u8`. Cross-platform (string-only). */
    private fun assertSegmentedVideoMapping(op: String, seg: SegmentedVideo) {
        assertTrue(
            seg.playlistPath.isNotBlank() && seg.segmentsPath.isNotBlank(),
            "$op: SegmentedVideo paths must be non-blank, was $seg",
        )
        assertTrue(
            seg.playlistPath != seg.segmentsPath,
            "$op: playlistPath and segmentsPath must differ, both were '${seg.playlistPath}'",
        )
        assertTrue(
            seg.playlistPath.endsWith(".m3u8"),
            "$op: playlistPath must be the .m3u8 playlist (fields swapped?), was '${seg.playlistPath}'",
        )
        assertTrue(
            !seg.segmentsPath.endsWith(".m3u8"),
            "$op: segmentsPath must not be the .m3u8 (fields swapped?), was '${seg.segmentsPath}'",
        )
    }

    /** Asserts a progress callback fired at least once (guaranteed by [FFmpegVideoCompressor]'s
     *  terminal `1f` on success) and that every reported fraction is in 0f..1f. */
    private fun assertProgressReported(op: String, values: List<Float>) {
        assertTrue(
            values.isNotEmpty(),
            "$op: progress callback never fired — performance-counter wiring is broken",
        )
        assertTrue(
            values.all { it in 0f..1f },
            "$op: progress fractions must be in 0f..1f, were $values",
        )
    }
}
