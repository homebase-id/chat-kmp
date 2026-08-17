package id.homebase.api.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure-Kotlin unit tests for [FfmpegCompressPlanner]. No platform deps, no
 * ffmpeg invocation — just verifies the planning logic that all three
 * `FFmpegUtils` actuals delegate to.
 *
 * Real-ffmpeg coverage lives in `FFmpegCompressBaselineJvmTest` (Desktop)
 * and `CompressVideoAndroidInstrumentedTest` (Android).
 */
class FfmpegCompressPlannerTest {

    // --- QualityTargets mapping ---

    fun targets_low_480p_125Mbps() {
        val t = VideoQuality.LOW.targets()
        assertEquals(480, t.shortEdgePx)
        assertEquals(1_250_000, t.videoBitrateBps)
        assertEquals(128_000, t.audioBitrateBps)
    }

    @Test
    fun targets_standard_720p_25Mbps() {
        val t = VideoQuality.STANDARD.targets()
        assertEquals(720, t.shortEdgePx)
        assertEquals(2_500_000, t.videoBitrateBps)
        assertEquals(128_000, t.audioBitrateBps)
    }

    @Test
    fun targets_high_1080p_5Mbps_192k() {
        val t = VideoQuality.HIGH.targets()
        assertEquals(1080, t.shortEdgePx)
        assertEquals(5_000_000, t.videoBitrateBps)
        assertEquals(192_000, t.audioBitrateBps)
    }

    @Test
    fun plan_inBudgetTenBit_stillReencodesToEightBit() {
        // The case #1278 shipped undecodable: a small, in-budget 10-bit clip. With no skip
        // path left it must always produce args, pinned to 8-bit.
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.STANDARD,
            trimStartMs = null, trimEndMs = null,
            probedWidthPx = 320, probedHeightPx = 180,
            probedCodecMime = "video/avc",
            inputDurationMs = 6_000L, inputBytes = 26_000L,
            probedBitDepth = 10,
        )
        assertTrue(plan.args.isNotEmpty(), "must always encode; args=${plan.args}")
        assertEquals("yuv420p", plan.args[plan.args.indexOf("-pix_fmt") + 1])
    }

    @Test
    fun plan_alreadyWithinEnvelope_stillEncodes() {
        // Small, 8-bit, SDR, in-budget H.264 — the shape that used to short-circuit.
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.STANDARD,
            trimStartMs = null, trimEndMs = null,
            probedWidthPx = 320, probedHeightPx = 180,
            probedCodecMime = "video/avc",
            inputDurationMs = 6_000L, inputBytes = 26_000L,
            probedBitDepth = 8, probedIsHdr = false,
        )
        assertTrue(plan.args.isNotEmpty(), "no pass-through may remain; args=${plan.args}")
        assertEquals("yuv420p", plan.args[plan.args.indexOf("-pix_fmt") + 1])
    }

    @Test
    fun outputDims_landscape_1080_to_720_downscale() {
        // 1920x1080 → STANDARD 720p target. Long edge auto-scaled.
        val dims = FfmpegCompressPlanner.computeOutputDims(1920, 1080, shortEdgePx = 720)
        assertNotNull(dims)
        assertEquals(720, dims.second)
        // 1920 * 720 / 1080 = 1280
        assertEquals(1280, dims.first)
    }

    @Test
    fun outputDims_portrait_1080_to_720_downscale() {
        // 1080x1920 portrait → STANDARD 720p target.
        val dims = FfmpegCompressPlanner.computeOutputDims(1080, 1920, shortEdgePx = 720)
        assertNotNull(dims)
        assertEquals(720, dims.first)
        // 1920 * 720 / 1080 = 1280
        assertEquals(1280, dims.second)
    }

    @Test
    fun outputDims_smallSource_returnsNull() {
        // 320x180 → STANDARD 720p target. Source short edge 180 ≤ 720 → no scale.
        assertNull(FfmpegCompressPlanner.computeOutputDims(320, 180, shortEdgePx = 720))
    }

    @Test
    fun outputDims_atTargetExactly_returnsNull() {
        // 1280x720 → STANDARD 720p target. Source short edge == target → no scale.
        assertNull(FfmpegCompressPlanner.computeOutputDims(1280, 720, shortEdgePx = 720))
    }

    @Test
    fun outputDims_evenRounding() {
        // 854x481 landscape → target 480 short edge.
        // h264 requires even W/H — both rounded up.
        // out_h = 480 (already even); out_w = 854 * 480 / 481 = 852.something → 852 (already even)
        // Let's use 853×481 to force a round-up.
        val dims = FfmpegCompressPlanner.computeOutputDims(853, 481, shortEdgePx = 480)
        assertNotNull(dims)
        assertEquals(480, dims.second)
        assertEquals(0, dims.first and 1, "width must be even, was ${dims.first}")
    }

    @Test
    fun outputDims_invalidSource_returnsNull() {
        assertNull(FfmpegCompressPlanner.computeOutputDims(0, 0, shortEdgePx = 720))
        assertNull(FfmpegCompressPlanner.computeOutputDims(-1, 100, shortEdgePx = 720))
    }

    // --- Plan arg list shape ---

    @Test
    fun plan_trim_alwaysReencodes_evenWhenAlreadyOptimal() {
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.STANDARD,
            trimStartMs = 1_000L, trimEndMs = 4_000L,
            probedWidthPx = 320, probedHeightPx = 180,
            probedCodecMime = "video/avc",
            inputDurationMs = 6_000L, inputBytes = 26_000L,
        )
        assertTrue(plan.args.isNotEmpty())
        // Trim flags present in correct order.
        val ssIdx = plan.args.indexOf("-ss")
        val toIdx = plan.args.indexOf("-to")
        assertTrue(ssIdx > 0 && toIdx > ssIdx, "args must contain -ss then -to in order; got ${plan.args}")
        assertEquals("1.000", plan.args[ssIdx + 1])
        assertEquals("4.000", plan.args[toIdx + 1])
    }

    @Test
    fun plan_libx264_includesPresetVeryfast() {
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.STANDARD,
            trimStartMs = 0L, trimEndMs = 5_000L,
            probedWidthPx = 1920, probedHeightPx = 1080,
            probedCodecMime = "video/avc",
            inputDurationMs = 10_000L, inputBytes = 10_000_000L,
            encoder = "libx264",
        )
        val presetIdx = plan.args.indexOf("-preset")
        assertTrue(presetIdx > 0, "libx264 must get -preset; args=${plan.args}")
        assertEquals("veryfast", plan.args[presetIdx + 1])
    }

    @Test
    fun plan_h264_videotoolbox_skipsPreset() {
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.STANDARD,
            trimStartMs = 0L, trimEndMs = 5_000L,
            probedWidthPx = 1920, probedHeightPx = 1080,
            probedCodecMime = "video/avc",
            inputDurationMs = 10_000L, inputBytes = 10_000_000L,
            encoder = "h264_videotoolbox",
        )
        assertFalse(
            plan.args.contains("-preset"),
            "h264_videotoolbox must NOT receive -preset (libx264-only); args=${plan.args}",
        )
        // But the encoder name still goes through.
        val vIdx = plan.args.indexOf("-c:v")
        assertEquals("h264_videotoolbox", plan.args[vIdx + 1])
    }

    @Test
    fun plan_downscale_emitsExplicitScaleWH() {
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.STANDARD,
            trimStartMs = 0L, trimEndMs = 5_000L,
            probedWidthPx = 1920, probedHeightPx = 1080,
            probedCodecMime = "video/avc",
            inputDurationMs = 10_000L, inputBytes = 10_000_000L,
        )
        val vfIdx = plan.args.indexOf("-vf")
        assertTrue(vfIdx > 0, "downscale source must get -vf; args=${plan.args}")
        // Explicit W:H, no filter expression with commas.
        assertEquals("scale=1280:720", plan.args[vfIdx + 1])
        // Resolved output dims surfaced for logging.
        assertEquals(1280 to 720, plan.outputDims)
    }

    @Test
    fun plan_smallSource_omitsScaleArgs() {
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.HIGH,  // 1080p target
            trimStartMs = 0L, trimEndMs = 5_000L,
            probedWidthPx = 320, probedHeightPx = 180,
            probedCodecMime = "video/avc",
            inputDurationMs = 6_000L, inputBytes = 26_000L,
        )
        assertFalse(plan.args.contains("-vf"), "source below target must omit -vf; args=${plan.args}")
        assertEquals(320 to 180, plan.outputDims)
    }

    @Test
    fun plan_bitrateAndAudioMatchTargets() {
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.HIGH,
            trimStartMs = 0L, trimEndMs = 5_000L,
            probedWidthPx = 1920, probedHeightPx = 1080,
            probedCodecMime = "video/avc",
            inputDurationMs = 10_000L, inputBytes = 10_000_000L,
        )
        val bvIdx = plan.args.indexOf("-b:v")
        val baIdx = plan.args.indexOf("-b:a")
        assertEquals("5000k", plan.args[bvIdx + 1])
        assertEquals("192k", plan.args[baIdx + 1])
    }

    @Test
    fun plan_outputPathIsLastArg() {
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.STANDARD,
            trimStartMs = 0L, trimEndMs = 5_000L,
            probedWidthPx = 1280, probedHeightPx = 720,
            probedCodecMime = "video/avc",
            inputDurationMs = 5_000L, inputBytes = 5_000_000L,
        )
        assertEquals("/out.mp4", plan.args.last())
    }

    @Test
    fun plan_rotation90_treatsRawLandscapeAsPortrait() {
        // Phone-camera portrait capture: container stores 1920×1080 + rotation=90.
        // Planner must reason in display orientation (1080×1920 portrait), not the
        // raw landscape dims — otherwise scale=1280:720 squishes the auto-rotated
        // frames back into landscape during compression.
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.STANDARD,
            trimStartMs = 0L, trimEndMs = 5_000L,
            probedWidthPx = 1920, probedHeightPx = 1080,
            probedCodecMime = "video/avc",
            inputDurationMs = 10_000L, inputBytes = 10_000_000L,
            rotationDegrees = 90,
        )
        val vfIdx = plan.args.indexOf("-vf")
        assertTrue(vfIdx > 0, "downscale source must get -vf; args=${plan.args}")
        assertEquals("scale=720:1280", plan.args[vfIdx + 1])
        assertEquals(720 to 1280, plan.outputDims)
    }

    @Test
    fun plan_rotation270_alsoSwaps() {
        // -90° / 270° same effect on aspect.
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.STANDARD,
            trimStartMs = 0L, trimEndMs = 5_000L,
            probedWidthPx = 1920, probedHeightPx = 1080,
            probedCodecMime = "video/avc",
            inputDurationMs = 10_000L, inputBytes = 10_000_000L,
            rotationDegrees = -90,
        )
        val vfIdx = plan.args.indexOf("-vf")
        assertEquals("scale=720:1280", plan.args[vfIdx + 1])
    }

    @Test
    fun plan_rotation180_doesNotSwap() {
        // Upside-down landscape: rotation 180 leaves the aspect alone.
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.STANDARD,
            trimStartMs = 0L, trimEndMs = 5_000L,
            probedWidthPx = 1920, probedHeightPx = 1080,
            probedCodecMime = "video/avc",
            inputDurationMs = 10_000L, inputBytes = 10_000_000L,
            rotationDegrees = 180,
        )
        val vfIdx = plan.args.indexOf("-vf")
        assertEquals("scale=1280:720", plan.args[vfIdx + 1])
    }

    @Test
    fun plan_alwaysPinsEightBitPixFmt() {
        // Every transcode must force 8-bit 4:2:0 output so a 10-bit/HDR source
        // can't round-trip as High 10 (which hardware AVC decoders reject).
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.STANDARD,
            trimStartMs = 0L, trimEndMs = 5_000L,
            probedWidthPx = 1280, probedHeightPx = 720,
            probedCodecMime = "video/avc",
            inputDurationMs = 5_000L, inputBytes = 5_000_000L,
        )
        val pixIdx = plan.args.indexOf("-pix_fmt")
        assertTrue(pixIdx > 0, "transcode must pin -pix_fmt; args=${plan.args}")
        assertEquals("yuv420p", plan.args[pixIdx + 1])
    }

    @Test
    fun plan_hdrInBudget_reencodesInsteadOfSkipping() {
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.STANDARD,
            trimStartMs = null, trimEndMs = null,
            probedWidthPx = 320, probedHeightPx = 180,
            probedCodecMime = "video/avc",
            inputDurationMs = 6_000L, inputBytes = 26_000L,
            probedIsHdr = true,
        )
        assertEquals("yuv420p", plan.args[plan.args.indexOf("-pix_fmt") + 1])
    }

    @Test
    fun plan_allowTenBit_overBudgetTenBit_reencodesToTenBitPixFmt() {
        // Over-budget (forces a real encode) 10-bit source with the flag on:
        // output must be pinned to 10-bit yuv420p10le, not downconverted.
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.STANDARD,
            trimStartMs = null, trimEndMs = null,
            probedWidthPx = 1920, probedHeightPx = 1080,
            probedCodecMime = "video/avc",
            inputDurationMs = 6_000L, inputBytes = 20_000_000L,
            probedBitDepth = 10,
            allowTenBit = true,
        )
        assertEquals("yuv420p10le", plan.args[plan.args.indexOf("-pix_fmt") + 1])
    }

    @Test
    fun plan_allowTenBit_hdr10Bit_stillReencodesButKeeps10Bit() {
        // HDR always forces a re-encode (no tone-map yet). With allowTenBit the
        // re-encode preserves the source's 10-bit depth (yuv420p10le) rather
        // than downconverting — so the 10-bit HDR pipeline can be inspected.
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.STANDARD,
            trimStartMs = null, trimEndMs = null,
            probedWidthPx = 320, probedHeightPx = 180,
            probedCodecMime = "video/avc",
            inputDurationMs = 6_000L, inputBytes = 26_000L,
            probedBitDepth = 10, probedIsHdr = true,
            allowTenBit = true,
        )
        assertEquals("yuv420p10le", plan.args[plan.args.indexOf("-pix_fmt") + 1])
    }

    @Test
    fun plan_allowTenBit_hdr8Bit_staysEightBit() {
        // The pix_fmt branch keys on bit depth, not HDR: an 8-bit HDR source
        // (e.g. 8-bit HLG) re-encodes but stays 8-bit.
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.STANDARD,
            trimStartMs = null, trimEndMs = null,
            probedWidthPx = 320, probedHeightPx = 180,
            probedCodecMime = "video/avc",
            inputDurationMs = 6_000L, inputBytes = 26_000L,
            probedBitDepth = 8, probedIsHdr = true,
            allowTenBit = true,
        )
        assertEquals("yuv420p", plan.args[plan.args.indexOf("-pix_fmt") + 1])
    }

    @Test
    fun plan_allowTenBit_eightBitSource_staysEightBit() {
        // Flag permits, not forces: an 8-bit source must remain 8-bit.
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.STANDARD,
            trimStartMs = 0L, trimEndMs = 5_000L,
            probedWidthPx = 1280, probedHeightPx = 720,
            probedCodecMime = "video/avc",
            inputDurationMs = 5_000L, inputBytes = 5_000_000L,
            probedBitDepth = 8,
            allowTenBit = true,
        )
        assertEquals("yuv420p", plan.args[plan.args.indexOf("-pix_fmt") + 1])
    }

    @Test
    fun plan_alwaysAddsMovflagsFaststart() {
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.STANDARD,
            trimStartMs = 0L, trimEndMs = 5_000L,
            probedWidthPx = 1280, probedHeightPx = 720,
            probedCodecMime = "video/avc",
            inputDurationMs = 5_000L, inputBytes = 5_000_000L,
        )
        val mfIdx = plan.args.indexOf("-movflags")
        assertTrue(mfIdx > 0)
        assertEquals("+faststart", plan.args[mfIdx + 1])
    }
}
