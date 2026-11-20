package id.homebase.api.video

import id.homebase.api.client.KeyHeader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [VideoCompressionService] end to end on each platform's real backend: AVFoundation on iOS, ffmpeg.wasm on web. The JVM
 * skips (no ffmpeg on this classpath; `FFmpegPipelineCoverageJvmTest` in homebase-chat covers it), and Android's
 * compressor has its own device tests. Each concern runs on the `.mp4` ([SampleVideoFixture]) and the QuickTime `.mov`
 * ([SampleMovFixture]): compress returns an output, segmenting maps the [SegmentedVideo] fields, progress fires, and
 * repeated compresses don't crash. A target whose stager returns null skips.
 */
class VideoCompressionCommonTest {

    // The single-threaded ffmpeg.wasm core takes 5-15 s on a 2-3 s clip.
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

    // ── re-entry ────────────────────────────────────────────────────────────────────────

    @Test
    fun reentry_mp4_repeatedCompressDoesNotCrash() = runTest(timeout = opTimeout) {
        runReentry(stageSampleVideoForFfmpegTest())
    }

    @Test
    fun reentry_mov_repeatedCompressDoesNotCrash() = runTest(timeout = opTimeout) {
        runReentry(stageSampleMovForFfmpegTest())
    }

    // ── shared bodies ───────────────────────────────────────────────────────────────────

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

    /** Several compresses of the same input back to back: the platform's engine must survive re-entry. */
    private suspend fun runReentry(input: String?) {
        if (input == null) return
        val produced = mutableListOf<String>()
        try {
            repeat(3) { i ->
                val out = VideoCompressionService.compress(
                    inputPath = input,
                    onProgress = null,
                    trimStartMs = 0L,
                    trimEndMs = 1_500L,
                    quality = VideoQuality.STANDARD,
                )
                assertNotNull(out, "compress #$i returned null — re-entry pipeline broken")
                produced += out
            }
        } finally {
            cleanupStagedSampleVideo(input)
            for (p in produced) cleanupStagedSampleVideo(p)
        }
    }

    /** Proves the [SegmentedVideo] fields aren't swapped. Null-tolerant:
     *  web's `segment*` returns null by design. */
    private suspend fun runSegmentMapping(input: String?) {
        if (input == null) return
        val produced = mutableListOf<String>()
        try {
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
     *  Every compressor guarantees a terminal `1f` on success, so this holds on every
     *  platform even where the backend's mid-run ticks are best-effort (a sub-second encode
     *  may emit none). Locks down that
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
            produced += out
            assertProgressReported("compress", compressProgress)

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

    /** Asserts a progress callback fired at least once (guaranteed by each compressor's
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
