@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

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

/** iOS test fixture staging: writes [SampleVideoFixture] bytes into NSCachesDirectory, the same bytes every platform reads. */
internal actual suspend fun stageSampleVideoForFfmpegTest(): String? =
    stageFixture(SampleVideoFixture.bytes, "mp4")

internal actual suspend fun stageSampleMovForFfmpegTest(): String? =
    stageFixture(SampleMovFixture.bytes, "mov")

internal actual suspend fun stagePosterVideoForTest(): String? =
    stageFixture(PosterVideoFixture.bytes, "mp4")

private fun stageFixture(bytes: ByteArray, ext: String): String? {
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
