package id.homebase.core.files

import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.files.Path

internal class InMemoryFileOps(private val canMove: Boolean = true) : FileOperationsProvider {
    private val store = mutableMapOf<String, ByteArray>()
    private var counter = 0

    // Keyed as kotlinx-io spells the path, so "/tmp/a" and Windows' "\tmp\a" are one file, as on a real disk.
    private val String.key get() = Path(this).toString()

    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String {
        val path = "/tmp/$prefix${counter++}$suffix"
        store[path.key] = bytes
        return path
    }

    override suspend fun readFileBytes(path: String): ByteArray =
        store[path.key] ?: error("file not found: $path")

    override fun getFileSize(path: String): Long = (store[path.key]?.size ?: 0).toLong()
    override fun deleteTempFile(path: String): Boolean = store.remove(path.key) != null
    override fun getCacheDirectory(): String = "/tmp"

    override fun openFileInput(path: String): InputProvider = throw NotImplementedError()
    override suspend fun writeBytesToShareOutboundFile(bytes: ByteArray, suffix: String): String =
        throw NotImplementedError()
    override suspend fun writeStream(path: String, data: Flow<ByteArray>) {
        var bytes = ByteArray(0)
        data.collect { bytes += it }
        store[path.key] = bytes
    }

    override fun readFileAsFlow(path: String, chunkSize: Int): Flow<ByteArray> =
        flow { emit(store[path.key] ?: throw IllegalStateException("file not found: $path")) }

    override suspend fun createShareOutboundPath(suffix: String): String = "/tmp/share_outbound/share_${counter++}$suffix"

    override suspend fun moveFile(from: String, to: String): Boolean {
        if (!canMove) return false
        store[to.key] = store.remove(from.key) ?: return false
        return true
    }
}
