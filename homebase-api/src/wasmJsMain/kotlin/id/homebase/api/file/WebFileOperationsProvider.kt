package id.homebase.api.file

import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.io.Buffer
import okio.buffer
import okio.Path.Companion.toPath
import kotlin.random.Random

/**
 * In-memory web implementation backed by the wasmJs [systemFileSystem] (an okio
 * `FakeFileSystem`). Everything lives in RAM and is cleared on page refresh — adequate for the
 * transient temp files the upload/share pipeline produces; durable browser storage
 * (IndexedDB / OPFS) is out of scope for the first-pass wasm support.
 */
class WebFileOperationsProvider : FileOperationsProvider {

    private val cacheDir = "/tmp/homebase"

    override fun openFileInput(path: String): InputProvider {
        val bytes = systemFileSystem.read(path.toPath()) { readByteArray() }
        return InputProvider(size = bytes.size.toLong()) { Buffer().apply { write(bytes) } }
    }

    override suspend fun readFileBytes(path: String): ByteArray =
        systemFileSystem.read(path.toPath()) { readByteArray() }

    override fun deleteTempFile(path: String): Boolean {
        val p = path.toPath()
        if (!systemFileSystem.exists(p)) return true
        return runCatching { systemFileSystem.delete(p); true }.getOrDefault(false)
    }

    override fun getCacheDirectory(): String = cacheDir

    override fun getFileSize(path: String): Long =
        systemFileSystem.metadataOrNull(path.toPath())?.size ?: 0L

    override suspend fun writeBytesToTempFile(
        bytes: ByteArray,
        prefix: String,
        suffix: String
    ): String {
        val dir = cacheDir.toPath()
        systemFileSystem.createDirectories(dir)
        val path = dir / "$prefix${randomToken()}$suffix"
        systemFileSystem.write(path) { write(bytes) }
        return path.toString()
    }

    override suspend fun writeBytesToShareOutboundFile(
        bytes: ByteArray,
        suffix: String,
    ): String {
        val dir = cacheDir.toPath() / SHARE_OUTBOUND_DIR_NAME
        systemFileSystem.createDirectories(dir)
        val path = dir / "share_${randomToken()}$suffix"
        systemFileSystem.write(path) { write(bytes) }
        return path.toString()
    }

    override suspend fun writeStream(
        path: String,
        data: Flow<ByteArray>
    ) {
        val p = path.toPath()
        p.parent?.let { systemFileSystem.createDirectories(it) }
        // okio's write{} block is non-suspend, so stream into a buffered sink instead.
        val sink = systemFileSystem.sink(p).buffer()
        try {
            data.collect { chunk -> sink.write(chunk) }
        } finally {
            sink.close()
        }
    }

    private fun randomToken(): String = Random.nextLong().toULong().toString(16)
}
