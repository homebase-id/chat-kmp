@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package id.homebase.api.video

import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile

/**
 * iOS test fixture staging. Writes [SampleVideoFixture] bytes into NSCachesDirectory so the
 * decoder reads the same `sample.mp4` bytes every other platform reads, then ensures the
 * shared `FFmpegKitBridgeHolder` has a real test bridge wired in — see [TestFFmpegKitBridge].
 *
 * On the macOS-host CI run for `iosSimulatorArm64Test`, this materializes the bridge against
 * the bundled `FFmpegKit.xcframework` checked in at `homebase-api/libs/`. On Linux hosts the
 * iOS targets are skipped entirely (cross-compilation not supported), so this code only runs
 * where it can actually link.
 */
internal actual suspend fun stageSampleVideoForFfmpegTest(): String? =
    stageFixture(SampleVideoFixture.bytes, "mp4")

internal actual suspend fun stageSampleMovForFfmpegTest(): String? =
    stageFixture(SampleMovFixture.bytes, "mov")

private fun stageFixture(bytes: ByteArray, ext: String): String? {
    installTestBridgeIfNeeded()

    val cacheDir = cacheDir()
    val path = "$cacheDir/vidfixture_${NSUUID.UUID().UUIDString}.$ext"

    val written = memScoped {
        val buffer = allocArrayOf(bytes)
        val data = NSData.create(bytes = buffer, length = bytes.size.toULong())
        data.writeToFile(path, true)
    }
    if (!written) return null
    return path
}

internal actual suspend fun cleanupStagedSampleVideo(path: String) {
    runCatching { NSFileManager.defaultManager.removeItemAtPath(path, null) }
}

private fun cacheDir(): String {
    val paths = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
    return paths.firstOrNull() as? String ?: NSTemporaryDirectory()
}

private var bridgeInstalled = false

private fun installTestBridgeIfNeeded() {
    if (bridgeInstalled) return
    FFmpegKitBridgeHolder.setBridge(TestFFmpegKitBridge())
    bridgeInstalled = true
}
