@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package id.homebase.api.video

import ffmpegkit.FFmpegKit
import ffmpegkit.FFmpegKitConfig
import ffmpegkit.ReturnCode

/**
 * Kotlin/Native test implementation of [FFmpegKitBridge]. Calls the FFmpegKit Objective-C API
 * through the cinterop bindings declared in `src/nativeTest/cinterop/ffmpegkit.def` so the
 * cross-platform `FfmpegDecoderCommonTest` exercises **real** FFmpegKit on iOS simulator,
 * not a mock.
 *
 * Cross-compilation to iOS isn't supported on Linux hosts (Kotlin/Native cinterop requires
 * Xcode tooling), so this file only compiles on macOS — CI's `iosSimulatorArm64Test` job
 * is where it gets exercised. Production iOS uses [FFmpegKitBridgeImpl] in Swift; this Kotlin
 * implementation only runs in tests.
 *
 * `executeFFmpegAsync` runs the command synchronously and fires `onComplete` immediately,
 * which is enough for the test path:
 *  - the strip decoder treats async as "run and emit progressively" via dir polling, but
 *    polling against an already-finished ffmpeg run just reads all the frames at once on the
 *    final sweep — same set of frames, same assertion.
 *  - `onProgress` is a no-op in tests (no progress UI to drive).
 *
 * `getMediaInformation` is unused in the thumbnail tests; we return null and let the decoder
 * fall through.
 */
internal class TestFFmpegKitBridge : FFmpegKitBridge {

    override fun executeFFmpeg(command: String): FFmpegResult {
        val session = FFmpegKit.execute(command)
        val isSuccess = ReturnCode.isSuccess(session?.getReturnCode())
        val failStackTrace = session?.getFailStackTrace()
        return FFmpegResult(isSuccess = isSuccess, failStackTrace = failStackTrace)
    }

    override fun executeFFmpegAsync(
        command: String,
        onProgress: (timeMs: Long) -> Unit,
        onComplete: (FFmpegResult) -> Unit,
    ): Long {
        // Sync-then-fire is sufficient for the cross-platform test surface. See class KDoc.
        val session = FFmpegKit.execute(command)
        val isSuccess = ReturnCode.isSuccess(session?.getReturnCode())
        val failStackTrace = session?.getFailStackTrace()
        onComplete(FFmpegResult(isSuccess = isSuccess, failStackTrace = failStackTrace))
        return session?.getSessionId() ?: -1L
    }

    override fun executeFFmpegAsyncArgs(
        args: List<String>,
        onProgress: (timeMs: Long) -> Unit,
        onComplete: (FFmpegResult) -> Unit,
    ): Long {
        val session = FFmpegKit.executeWithArguments(args)
        val isSuccess = ReturnCode.isSuccess(session?.getReturnCode())
        val failStackTrace = session?.getFailStackTrace()
        onComplete(FFmpegResult(isSuccess = isSuccess, failStackTrace = failStackTrace))
        return session?.getSessionId() ?: -1L
    }

    override fun cancelAllFFmpegSessions() {
        FFmpegKit.cancel()
    }

    override fun cancelFFmpegSession(sessionId: Long) {
        // No-op in sync test bridge — session already complete.
    }

    override fun getMediaInformation(filePath: String): MediaInfo? = null

    override fun getFfmpegVersionBanner(): String? = FFmpegKitConfig.getFFmpegVersion()
}
