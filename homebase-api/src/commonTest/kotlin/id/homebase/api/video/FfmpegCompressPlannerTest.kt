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

    @Test
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
    fun plan_alwaysEncodes_toEightBitSdr() {
        // The invariant, stated as one test: plan() accepts no codec/bit-depth/HDR input at
        // all, so there is no source — 10-bit, HDR, in-budget, whatever — that yields
        // anything but an 8-bit BT.709 encode. This is the shape that used to short-circuit
        // and ship undecodable High-10 (#1278).
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.STANDARD,
            trimStartMs = null, trimEndMs = null,
            probedWidthPx = 320, probedHeightPx = 180,
        )
        assertTrue(plan.args.isNotEmpty(), "no pass-through may remain; args=${plan.args}")
        assertEquals("yuv420p", plan.args[plan.args.indexOf("-pix_fmt") + 1])
        assertEquals("bt709", plan.args[plan.args.indexOf("-colorspace") + 1])
        assertEquals("bt709", plan.args[plan.args.indexOf("-color_primaries") + 1])
        assertEquals("bt709", plan.args[plan.args.indexOf("-color_trc") + 1])
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
        )
        assertEquals("yuv420p", plan.args[plan.args.indexOf("-pix_fmt") + 1])
    }

    @Test
    fun plan_alwaysStripsSourceMetadata() {
        // ffmpeg defaults to -map_metadata 0, which copies the camera's location atom into
        // the output. Every encode must disable it, trim or not (#1294).
        for (trim in listOf(null to null, 0L to 5_000L)) {
            val plan = FfmpegCompressPlanner.plan(
                inputPath = "/in.mp4", outputPath = "/out.mp4",
                quality = VideoQuality.STANDARD,
                trimStartMs = trim.first, trimEndMs = trim.second,
                probedWidthPx = 1920, probedHeightPx = 1080,
            )
            val idx = plan.args.indexOf("-map_metadata")
            assertTrue(idx >= 0, "must strip source metadata; args=${plan.args}")
            assertEquals("-1", plan.args[idx + 1])
            // Must precede the output path, or ffmpeg treats it as an input option.
            assertTrue(idx < plan.args.lastIndex, "-map_metadata must come before the output")
        }
    }

    @Test
    fun plan_alwaysAddsMovflagsFaststart() {
        val plan = FfmpegCompressPlanner.plan(
            inputPath = "/in.mp4", outputPath = "/out.mp4",
            quality = VideoQuality.STANDARD,
            trimStartMs = 0L, trimEndMs = 5_000L,
            probedWidthPx = 1280, probedHeightPx = 720,
        )
        val mfIdx = plan.args.indexOf("-movflags")
        assertTrue(mfIdx > 0)
        assertEquals("+faststart", plan.args[mfIdx + 1])
    }
}
