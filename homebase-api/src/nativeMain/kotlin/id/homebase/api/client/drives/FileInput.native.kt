package id.homebase.api.client.drives


import io.ktor.client.request.forms.InputProvider
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy
import platform.Foundation.*

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
actual fun openFileInput(path: String): InputProvider =
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
actual suspend fun readFileBytes(path: String): ByteArray {
    val data = NSData.dataWithContentsOfFile(path)
        ?: error("Unable to read file at $path")

    val bytes = ByteArray(data.length.toInt())
    bytes.usePinned { pinned ->
        memcpy(pinned.addressOf(0), data.bytes, data.length)
    }
    return bytes
}

@OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)
actual suspend fun writeBytesToTempFile(
    bytes: ByteArray,
    prefix: String,
    suffix: String
): String {
    val tempDir = NSTemporaryDirectory()
    val filePath = "$tempDir$prefix${NSUUID().UUIDString}$suffix"

    val data =
        bytes.usePinned { pinned ->
            NSData.create(
                bytes = pinned.addressOf(0),
                length = bytes.size.toULong()
            )
        }

    data.writeToFile(filePath, atomically = true)
    return filePath
}