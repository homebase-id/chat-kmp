package id.homebase.api.file

import io.ktor.client.request.forms.InputProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

class IOSFileOperationsProvider : FileOperationsProvider {
    @OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
    override fun openFileInput(path: String): InputProvider = InputProvider {
        val data = NSData.dataWithContentsOfFile(path) ?: error("Unable to read file at $path")

        val bytes = ByteArray(data.length.toInt())
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }

        Buffer().apply { write(bytes) }
    }

    @OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
    override suspend fun readFileBytes(path: String): ByteArray {
        val data = NSData.dataWithContentsOfFile(path) ?: error("Unable to read file at $path")

        val bytes = ByteArray(data.length.toInt())
        bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }
        return bytes
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun deleteTempFile(path: String): Boolean {
        return runCatching {
            val fileManager = NSFileManager.defaultManager

            if (!fileManager.fileExistsAtPath(path)) {
                true
            } else {
                fileManager.removeItemAtPath(path, error = null)
            }
        }.getOrDefault(false)
    }


    @OptIn(ExperimentalForeignApi::class)
    override fun getCacheDirectory(): String {
        val fileManager = NSFileManager.defaultManager
        val cacheUrl =
            fileManager.URLForDirectory(
                directory = NSCachesDirectory,
                inDomain = NSUserDomainMask,
                appropriateForURL = null,
                create = true,
                error = null
            )
        return cacheUrl?.path ?: NSTemporaryDirectory()
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun getFileSize(path: String): Long {
        val attrs =
            NSFileManager.defaultManager.attributesOfItemAtPath(
                path = path,
                error = null
            )

        val size = attrs?.get("NSFileSize") as? NSNumber
        return size?.longLongValue ?: 0L
    }

}
