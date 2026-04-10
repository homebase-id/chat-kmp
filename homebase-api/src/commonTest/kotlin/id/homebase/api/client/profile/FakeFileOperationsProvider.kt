package id.homebase.api.client.profile

import id.homebase.api.file.FileOperationsProvider
import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow

class FakeFileOperationsProvider : FileOperationsProvider {
    override fun getCacheDirectory(): String = "/tmp/test-cache"
    override fun openFileInput(path: String): InputProvider = throw UnsupportedOperationException()
    override suspend fun readFileBytes(path: String): ByteArray = throw UnsupportedOperationException()
    override fun deleteTempFile(path: String): Boolean = false
    override fun getFileSize(path: String): Long = 0L
    override suspend fun writeBytesToTempFile(bytes: ByteArray, prefix: String, suffix: String): String = throw UnsupportedOperationException()
    override suspend fun writeStream(path: String, data: Flow<ByteArray>) = throw UnsupportedOperationException()
    override fun readFileHeaderBytes(path: String, count: Int): ByteArray = ByteArray(0)
}
