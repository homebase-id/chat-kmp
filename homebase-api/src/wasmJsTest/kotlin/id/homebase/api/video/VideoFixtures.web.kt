@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package id.homebase.api.video

import id.homebase.api.file.systemFileSystem
import kotlinx.coroutines.delay
import okio.Path.Companion.toPath

/**
 * Web fixture staging. The mirrored `odin-ffmpeg.js` (see the
 * `mirrorWebAppFfmpegAssetsForTest` gradle task and `karma.config.d/01-ffmpeg.js`) installs
 * the `__odinFfmpeg` bridge on the Karma browser; we wait briefly for it to settle, then
 * materialize [SampleVideoFixture] bytes into the okio FakeFileSystem so the decoder can
 * read them at the returned path.
 */
internal actual suspend fun stageSampleVideoForFfmpegTest(): String? {
    if (!awaitFfmpegBridge()) return null

    val path = "/tmp/test_videos/sample.mp4"
    val okioPath = path.toPath()
    runCatching {
        okioPath.parent?.let { systemFileSystem.createDirectories(it) }
        systemFileSystem.write(okioPath) { write(SampleVideoFixture.bytes) }
    }.onFailure { return null }
    return path
}

internal actual suspend fun cleanupStagedSampleVideo(path: String) {
    runCatching { systemFileSystem.delete(path.toPath()) }
}

/**
 * Polls [hasFfmpegBridge] for up to [timeoutMs] before giving up. The bridge JS files are
 * loaded by Karma before tests start, but `__odinFfmpeg` is assigned inside an IIFE that
 * runs synchronously on script eval — by the time the test begins the bridge is normally
 * already present. The poll covers race-condition edge cases.
 */
private suspend fun awaitFfmpegBridge(timeoutMs: Long = 5_000L): Boolean {
    var waited = 0L
    val step = 50L
    while (waited <= timeoutMs) {
        if (hasFfmpegBridge()) return true
        delay(step)
        waited += step
    }
    return false
}

private fun hasFfmpegBridge(): Boolean = js("typeof globalThis.__odinFfmpeg === 'object' && globalThis.__odinFfmpeg !== null")
