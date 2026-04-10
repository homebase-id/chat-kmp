package id.homebase.api.file

import android.content.Context
import androidx.core.net.toUri
import io.ktor.client.request.forms.InputProvider
import io.ktor.utils.io.streams.asInput
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import java.io.FileOutputStream

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

    override suspend fun readFileBytes(path: String): ByteArray = withContext(Dispatchers.IO) {
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

    override fun getFileSize(path: String): Long {
        if (path.startsWith("content://") || path.startsWith("content:")) {
            val uri = path.toUri()

            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)

                if (sizeIndex >= 0 && cursor.moveToFirst()) {
                    return cursor.getLong(sizeIndex)
                }
            }

            return 0L
        }

        return File(path).length()
    }

    override suspend fun writeBytesToTempFile(
        bytes: ByteArray, prefix: String, suffix: String
    ): String = withContext(Dispatchers.IO) {
        val file = File.createTempFile(prefix, suffix, context.cacheDir)
        file.writeBytes(bytes)
        file.path
    }

    override suspend fun resolveToFilePath(path: String): String {
        if (!path.startsWith("content://") && !path.startsWith("content:")) return path
        return withContext(Dispatchers.IO) {
            val uri = path.toUri()
            val ext = context.contentResolver.getType(uri)
                ?.substringAfterLast('/')
                ?.let { ".$it" } ?: ""
            val tmp = File.createTempFile("resolved_", ext, context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalArgumentException("Unable to open content URI: $path")
            tmp.absolutePath
        }
    }

    override fun readFileHeaderBytes(path: String, count: Int): ByteArray {
        if (path.startsWith("content://") || path.startsWith("content:")) {
            val uri = path.toUri()
            return context.contentResolver.openInputStream(uri)?.use { input ->
                val buf = ByteArray(count)
                val read = input.read(buf, 0, count)
                if (read <= 0) ByteArray(0) else buf.copyOf(read)
            } ?: ByteArray(0)
        }
        val file = File(path)
        if (!file.exists()) return ByteArray(0)
        return file.inputStream().use { input ->
            val buf = ByteArray(count)
            val read = input.read(buf, 0, count)
            if (read <= 0) ByteArray(0) else buf.copyOf(read)
        }
    }

    override suspend fun writeStream(
        path: String,
        data: Flow<ByteArray>
    ) = withContext(Dispatchers.IO) {

        if (path.startsWith("content://") || path.startsWith("content:")) {

            val uri = path.toUri()

            context.contentResolver.openOutputStream(uri)?.use { out ->
                data.collect { chunk ->
                    out.write(chunk)
                }
            } ?: throw IllegalArgumentException("Unable to open content URI for write: $path")

        } else {

            FileOutputStream(File(path)).use { out ->
                data.collect { chunk ->
                    out.write(chunk)
                }
            }

        }
    }
}
