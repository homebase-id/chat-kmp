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

    override fun readFileAsFlow(path: String, chunkSize: Int): kotlinx.coroutines.flow.Flow<ByteArray> =
        kotlinx.coroutines.flow.flow {
            File(path).inputStream().use { stream ->
                val buf = ByteArray(chunkSize)
                while (true) {
                    val n = stream.read(buf, 0, buf.size)
                    if (n <= 0) break
                    emit(buf.copyOf(n))
                }
            }
        }

    override suspend fun readFileHeaderBytes(path: String, maxBytes: Int): ByteArray =
        withContext(Dispatchers.IO) {
            File(path).inputStream().use { stream ->
                val buf = ByteArray(maxBytes)
                var off = 0
                while (off < maxBytes) {
                    val n = stream.read(buf, off, maxBytes - off)
                    if (n <= 0) break
                    off += n
                }
                if (off == maxBytes) buf else buf.copyOf(off)
            }
        }

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
