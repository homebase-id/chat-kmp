package id.homebase.api.file

import android.content.Context
import androidx.core.net.toUri
import io.ktor.client.request.forms.InputProvider
import io.ktor.utils.io.streams.asInput
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidFileOperationsProvider(
    val context: Context,
) : FileOperationsProvider {
    override fun openFileInput(path: String): InputProvider {
        return InputProvider {
            if (path.startsWith("content://") || path.startsWith("content:")) {
                val uri = path.toUri()
                context.contentResolver.openInputStream(uri)?.asInput()
                    ?: throw IllegalArgumentException("Unable to open content URI: $path")
            } else {
                File(path).inputStream().asInput()
            }
        }
    }

    override suspend fun readFileBytes(path: String): ByteArray =
        withContext(Dispatchers.IO) {
            if (path.startsWith("content://") || path.startsWith("content:")) {
                val uri = path.toUri()
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalArgumentException("Unable to read content URI: $path")
            } else {
                File(path).readBytes()
            }
        }

    override fun deleteTempFile(path: String): Boolean {
        return runCatching {
            if (path.startsWith("content://") || path.startsWith("content:")) {
                // You generally do NOT own content URIs — never delete them
                false
            } else {
                val file = File(path)

                if (!file.exists() || file.isDirectory) {
                    true
                } else if (file.delete()) {
                    true
                } else {
                    // No deleteOnExit on Android — JVM exit is not reliable
                    false
                }
            }
        }.getOrDefault(false)
    }


    override fun getCacheDirectory(): String = context.cacheDir.absolutePath
}
