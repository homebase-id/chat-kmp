@file:OptIn(LowLevelFfmpegApi::class)

package id.homebase.api.video

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Regression coverage for the iPhone `.mov`-send crash fix. When FFmpegKit's bundled ffprobe
 * (`getMediaInformation`) can't read a container — the production failure for 4K HEVC + `apac`
 * spatial-audio + `mebx` captures — [FFmpegUtils.probeVideo] must fall back to AVFoundation and
 * still return real track dimensions. Without real dims the compress planner emits a scale-less
 * command and `h264_videotoolbox` SIGSEGVs (see the encoder selection in `FFmpegUtils.native.kt`).
 *
 * [TestFFmpegKitBridge.getMediaInformation] returns null, so this reproduces the empty-ffprobe
 * condition deterministically against the bundled `.mov` fixture and proves the AVFoundation
 * branch of `probeVideoNative` actually reads the track. The Swift `FFmpegPipelineTests` assert
 * the same behaviour against the real 4K spatial file but *inline* the AVFoundation calls; this
 * is the only test that drives the Kotlin `probeVideo` decision (ffprobe-first / AVFoundation-
 * fallback) end to end.
 *
 * iOS-only (nativeTest): the AVFoundation + FFmpegKit cinterop only links on a macOS host, so
 * Linux dev builds skip iosSimulatorArm64Test entirely — CI's macOS runner exercises it.
 */
class FFmpegProbeFallbackNativeTest {

    @AfterTest
    fun restoreProductionTestBridge() {
        // Each test mutates the process-global bridge; restore the standard one so neighbours
        // running afterward in the same process see the bridge they expect.
        FFmpegKitBridgeHolder.setBridge(TestFFmpegKitBridge())
    }

    @Test
    fun probeVideo_fallsBackToAVFoundation_whenFfprobeYieldsNothing() = runBlocking {
        // sample.mov: QuickTime H.264, 160x90, no rotation (see SampleMovFixture).
        val path = stageSampleMovForFfmpegTest() ?: return@runBlocking // target not wired → skip green
        try {
            // Guarantee the empty-ffprobe condition regardless of test ordering: this bridge's
            // getMediaInformation() returns null, so probeVideoNative must take the AVFoundation
            // path rather than trusting ffprobe.
            FFmpegKitBridgeHolder.setBridge(TestFFmpegKitBridge())

            val info = FFmpegUtils.probeVideo(path)

            assertNotNull(
                info,
                "probeVideo must return non-null via the AVFoundation fallback when ffprobe yields nothing",
            )
            assertEquals(160, info.widthPx, "AVFoundation must read the real width from the .mov video track")
            assertEquals(90, info.heightPx, "AVFoundation must read the real height from the .mov video track")
            // The AVFoundation fallback can't read pixel format, so it reports bit depth / HDR as
            // null ("undeterminable") — the planner then fails closed and re-encodes rather than
            // assuming 8-bit SDR (#959). (This path re-encodes regardless: codec is null too.)
            assertNull(info.bitDepth, "AVFoundation fallback can't determine bit depth → null")
            assertNull(info.isHdr, "AVFoundation fallback can't determine HDR → null")
        } finally {
            cleanupStagedSampleVideo(path)
        }
    }
}
