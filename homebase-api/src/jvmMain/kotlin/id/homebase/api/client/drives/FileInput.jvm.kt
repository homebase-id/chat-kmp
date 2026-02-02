package id.homebase.api.client.drives

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// androidMain / desktopMain
actual suspend fun writeBytesToTempFile(
    bytes: ByteArray,
    prefix: String,
    suffix: String
): String =
    withContext(Dispatchers.IO) {
        val file = java.io.File.createTempFile(prefix, suffix)
        file.writeBytes(bytes)
        file.absolutePath
    }

