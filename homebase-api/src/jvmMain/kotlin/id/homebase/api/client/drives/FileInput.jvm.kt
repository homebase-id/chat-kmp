package id.homebase.api.client.drives


import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual fun openFileInput(path: String): InputProvider =
    InputProvider {
        File(path).inputStream().asSource().buffered()
    }

// androidMain / desktopMain
actual suspend fun readFileBytes(path: String): ByteArray =
    withContext(Dispatchers.IO) {
        java.io.File(path).readBytes()
    }

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

