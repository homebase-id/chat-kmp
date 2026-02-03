package id.homebase.api.client.drives

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile

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