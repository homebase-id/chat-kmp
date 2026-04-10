package id.homebase.api.file

import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow

interface FileOperationsProvider {
    fun openFileInput(path: String): InputProvider
    suspend fun readFileBytes(path: String): ByteArray

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
}
