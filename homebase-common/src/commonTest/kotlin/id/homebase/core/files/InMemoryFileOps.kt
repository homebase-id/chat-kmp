package id.homebase.core.files

import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow

internal class InMemoryFileOps : FileOperationsProvider {
    private val store = mutableMapOf<String, ByteArray>()
    private var counter = 0

    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String {
        val path = "/tmp/$prefix${counter++}$suffix"
        store[path] = bytes
        return path
    }

    override suspend fun readFileBytes(path: String): ByteArray =
        store[path] ?: error("file not found: $path")

    override fun getFileSize(path: String): Long = (store[path]?.size ?: 0).toLong()
    override fun deleteTempFile(path: String): Boolean = store.remove(path) != null
    override fun getCacheDirectory(): String = "/tmp"

    override fun openFileInput(path: String): InputProvider = throw NotImplementedError()
    override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String =
        throw NotImplementedError()
    override suspend fun writeStream(path: String, data: Flow<ByteArray>) = throw NotImplementedError()
}
