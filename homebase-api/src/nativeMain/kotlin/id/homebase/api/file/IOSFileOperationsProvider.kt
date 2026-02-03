package id.homebase.api.file

import io.ktor.client.request.forms.InputProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy

class IOSFileOperationsProvider : FileOperationsProvider {
    @OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
    override fun openFileInput(path: String): InputProvider =
        InputProvider {
            val data = NSData.dataWithContentsOfFile(path)
                ?: error("Unable to read file at $path")

            val bytes = ByteArray(data.length.toInt())
            bytes.usePinned { pinned ->
                memcpy(pinned.addressOf(0), data.bytes, data.length)
            }

            Buffer().apply {
                write(bytes)
            }
        }

    @OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
    override suspend fun readFileBytes(path: String): ByteArray {
        val data = NSData.dataWithContentsOfFile(path)
            ?: error("Unable to read file at $path")

        val bytes = ByteArray(data.length.toInt())
        bytes.usePinned { pinned ->
            memcpy(pinned.addressOf(0), data.bytes, data.length)
        }
        return bytes
    }
}