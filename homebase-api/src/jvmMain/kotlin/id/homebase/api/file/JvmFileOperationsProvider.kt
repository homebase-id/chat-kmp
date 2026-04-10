package id.homebase.api.file

import io.ktor.client.request.forms.InputProvider
import io.ktor.utils.io.streams.asInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

class JvmFileOperationsProvider : FileOperationsProvider {
    override fun openFileInput(path: String): InputProvider {
        val inputProvider = InputProvider { File(path).inputStream().asInput() }
        return inputProvider
    }

    override suspend fun readFileBytes(path: String): ByteArray =
        withContext(Dispatchers.IO) { File(path).readBytes() }

    override fun deleteTempFile(path: String): Boolean {
        val file = File(path)

        if (!file.exists() || file.isDirectory) return true

        return runCatching {
            if (file.delete()) {
                true
            } else {
                file.deleteOnExit()
                false
            }
        }.getOrDefault(false)
    }


    override fun getCacheDirectory(): String {
        return JvmFileSystemUtil.getCacheDirectory().absolutePath
    }

    override fun getFileSize(path: String): Long {
        val file = File(path)
        return if (file.exists() && file.isFile) file.length() else 0L
    }

    override suspend fun writeBytesToTempFile(
        bytes: ByteArray,
        prefix: String,
        suffix: String
    ): String =
        withContext(Dispatchers.IO) {
            val file = File.createTempFile(prefix, suffix)
            file.writeBytes(bytes)
            file.absolutePath
        }

    override fun readFileHeaderBytes(path: String, count: Int): ByteArray {
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
        val file = File(path)
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { out ->
            data.collect { out.write(it) }
        }
    }
}
