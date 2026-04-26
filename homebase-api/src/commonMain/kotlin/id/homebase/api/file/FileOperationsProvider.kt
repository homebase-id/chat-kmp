package id.homebase.api.file

import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface FileOperationsProvider {
    fun openFileInput(path: String): InputProvider
    suspend fun readFileBytes(path: String): ByteArray

    /**
     * Read [path] as a stream of byte chunks (default 64 KB). Lets callers process large
     * files (video encryption, hash, upload) without allocating the full payload at once.
     *
     * The default implementation falls back to [readFileBytes] and emits a single chunk —
     * no streaming benefit, but no regression for platforms that haven't bothered to
     * override. Android and JVM provide proper streaming.
     */
    fun readFileAsFlow(path: String, chunkSize: Int = DEFAULT_HEADER_BYTES): Flow<ByteArray> = flow {
        emit(readFileBytes(path))
    }

    /**
     * Read at most [maxBytes] from the start of [path]. Designed for cheap header peeks
     * (image dimensions, MIME sniffing) where loading the full asset would waste RAM.
     *
     * The default implementation reads everything and slices — platforms with cheap
     * streaming I/O (Android, JVM) are expected to override.
     */
    suspend fun readFileHeaderBytes(path: String, maxBytes: Int = DEFAULT_HEADER_BYTES): ByteArray {
        val all = readFileBytes(path)
        return if (all.size <= maxBytes) all else all.copyOf(maxBytes)
    }

    fun deleteTempFile(path: String): Boolean
    fun getCacheDirectory(): String

    fun getFileSize(path: String): Long

    suspend fun writeBytesToTempFile(
        bytes: ByteArray,
        prefix: String,
        suffix: String
    ): String

    suspend fun writeStream(
        path: String,
        data: Flow<ByteArray>
    )

    /**
     * Resolves a path that may be a content URI (Android) to a real filesystem path
     * by copying it to a temp file. On platforms without content URIs, returns [path] unchanged.
     */
    suspend fun resolveToFilePath(path: String): String = path

    companion object {
        const val DEFAULT_HEADER_BYTES: Int = 64 * 1024
    }
}
