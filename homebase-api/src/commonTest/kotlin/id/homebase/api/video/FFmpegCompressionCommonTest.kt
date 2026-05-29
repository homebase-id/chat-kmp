package id.homebase.api.video

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * One generic test that exercises [FFmpegUtils.compressVideo] end-to-end through the
 * platform's *real* ffmpeg infrastructure — the same actual production code calls.
 *
 * This is the test that would have caught the iOS bridge crash this PR initially shipped.
 * The previous coverage gap: every video-related test (JVM, native, wasm) exercised either
 * the thumbnail-extractor surface or talked to FFmpegKit directly, but nothing went through
 * `FFmpegUtils.compressVideo` — the function `MessageAttachmentBuilder` /
 * `MomentsPostSenderService` use to compress every uploaded video. Both Chat AND Moments
 * route through the same `VideoPayloadProcessor → FFmpegUtils.compressVideo` chain, so a
 * single common test covers both surfaces.
 *
 * **Per-platform routing — reuses the same staging seam as [FfmpegDecoderCommonTest]:**
 *
 * | Target                 | What this test exercises                                                                                |
 * |------------------------|---------------------------------------------------------------------------------------------------------|
 * | iOS sim (nativeTest)   | ✅ Real ffmpeg via [TestFFmpegKitBridge] (Kotlin/Native cinterop → FFmpegKit). **NOT the Swift bridge.** |
 * | Web (wasmJsTest)       | ✅ Real ffmpeg.wasm via `globalThis.__odinFfmpeg` (Karma loads the bridge before the test runs)         |
 * | JVM (homebase-api jvmTest) | ⚠️ Silent skip — homebase-api's classpath doesn't include ffmpeg binaries. JVM compression is covered by **`FFmpegPipelineCoverageJvmTest` in homebase-chat:jvmTest**, where the binaries live (`homebase-chat/src/jvmMain/resources/ffmpeg/`). |
 * | Android host           | ⚠️ Silent skip — no ffmpeg infra in androidHostTest classpath                                          |
 * | Android device         | ⏸️ FFmpegKit AAR is on classpath but `CompressVideoAndroidInstrumentedTest` is `@Ignore`'d until CI emulator infra lands |
 *
 * **iOS Swift-bridge coverage caveat — load-bearing.** This test routes through
 * `TestFFmpegKitBridge`, a Kotlin/Native cinterop bridge. Production iOS installs
 * `FFmpegKitBridgeImpl.swift` — a separate Swift class that calls FFmpegKit's Swift API.
 * They are byte-different paths into the same FFmpegKit framework. The crash that prompted
 * this PR to revert `executeFFmpegAsyncArgs` would NOT have been caught here — only the
 * `iosApp/iosAppTests/FFmpegKitBridgeImplTests.swift` XCTest exercises the Swift path.
 *
 * iOS is the only platform with this duality: Android tests use the same FFmpegKit Java
 * API as production, JVM tests use the same `ProcessBuilder` shape as production, Web tests
 * use the same `__odinFfmpeg` bridge as production. Only iOS has a Swift wrapper between
 * Kotlin and FFmpegKit, so only iOS needs the XCTest *in addition* to this commonTest.
 *
 * **What this test does catch.** Kotlin-level regressions in `FFmpegUtils.compressVideo`,
 * `FfmpegCompressPlanner`, or in the per-platform actuals' command construction — exactly
 * the kind of "did we wire the bridge call shape right" issue the iOS revert in this PR
 * had to chase manually because no test guarded the path.
 */
class FFmpegCompressionCommonTest {

    @Test
    fun compressVideo_withTrim_producesOutputPath() = runTest(
        // ffmpeg run on a 3 s trim of a 320x180 H.264 clip is sub-second on JVM/iOS sim,
        // ~5-15 s on the ffmpeg.wasm single-threaded core. Bumping the dispatcher timeout
        // so the wasm leg doesn't false-timeout out.
        timeout = kotlin.time.Duration.parse("60s"),
    ) {
        val input = stageSampleVideoForFfmpegTest() ?: return@runTest
        var output: String? = null
        try {
            // Trim forces ffmpeg to re-encode (skipping the planner's "already optimal"
            // short-circuit), so a non-null return proves the full bridge → ffmpeg →
            // output-file pipeline ran end-to-end. With no trim, the planner could
            // legitimately return null for "input is already small enough" and we'd
            // get a false test pass.
            output = FFmpegUtils.compressVideo(
                inputPath = input,
                onProgress = null,
                trimStartMs = 0L,
                trimEndMs = 3_000L,
                quality = VideoQuality.STANDARD,
            )
            assertNotNull(output, "compressVideo returned null — compression pipeline broken")
        } finally {
            cleanupStagedSampleVideo(input)
            // The cleanup helper uses each platform's native delete (NSFileManager,
            // File.delete, systemFileSystem.delete) — works on output paths too.
            output?.let { cleanupStagedSampleVideo(it) }
        }
    }
}
