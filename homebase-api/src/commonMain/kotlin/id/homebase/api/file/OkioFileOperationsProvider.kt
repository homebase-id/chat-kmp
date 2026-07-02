package id.homebase.api.file

import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.io.Buffer
import okio.FileSystem
import okio.buffer
import okio.Path.Companion.toPath
import kotlin.random.Random

/**
 * okio-backed [FileOperationsProvider]. The [fileSystem] is injected, so the exact same logic runs
 * over any okio `FileSystem` — a real disk, the web's in-memory `FakeFileSystem`, or a test
 * `FakeFileSystem`. The web provider ([WebFileOperationsProvider]) is just this bound to the wasm
 * [systemFileSystem]; keeping the logic here (not in the wasm-only source set) lets it be unit
 * tested generically against a `FakeFileSystem` without any wasm test harness.
 */
open class OkioFileOperationsProvider(
    private val fileSystem: FileSystem,
    private val cacheDir: String,
) : FileOperationsProvider {

    override fun openFileInput(path: String): InputProvider {
        val bytes = fileSystem.read(path.toPath()) { readByteArray() }
        return InputProvider(size = bytes.size.toLong()) { Buffer().apply { write(bytes) } }
    }

    override suspend fun readFileBytes(path: String): ByteArray =
        fileSystem.read(path.toPath()) { readByteArray() }

    override fun deleteTempFile(path: String): Boolean {
        val p = path.toPath()
        if (!fileSystem.exists(p)) return true
        return runCatching { fileSystem.delete(p); true }.getOrDefault(false)
    }

    override fun getCacheDirectory(): String = cacheDir

    override fun getFileSize(path: String): Long =
        fileSystem.metadataOrNull(path.toPath())?.size ?: 0L

    override suspend fun sourceExists(path: String): Boolean =
        fileSystem.exists(path.toPath())

    override suspend fun writeBytesToTempFile(
        bytes: ByteArray,
        prefix: String,
        suffix: String
    ): String {
        return writeBytesIn(CacheAudit.UPLOAD_TEMP_DIR_NAME, bytes, prefix, suffix)
    }

    // Staging location keeps the interface default (<cacheDir>/outbox-temp — on web the whole
    // FS is a RAM FakeFileSystem, so true durability is deferred; see the interface KDoc), but
    // the path/promote operations must run over the INJECTED fileSystem, not the global
    // systemFileSystem the interface defaults use — otherwise web/test writes would land on the
    // wrong filesystem.
    override suspend fun createOutboxStagingPath(prefix: String, suffix: String): String =
        createStagingPathIn(getOutboxStagingDirectory(), prefix, suffix, fileSystem)

    override suspend fun promoteToOutboxStaging(path: String): String =
        promoteIntoStaging(path, getOutboxStagingDirectory(), fileSystem)

    // upload-temp is swept every startup (disposable).
    private fun writeBytesIn(dirName: String, bytes: ByteArray, prefix: String, suffix: String): String {
        val dir = cacheDir.toPath() / dirName
        fileSystem.createDirectories(dir)
        val path = dir / "$prefix${randomToken()}$suffix"
        fileSystem.write(path) { write(bytes) }
        return path.toString()
    }

    override suspend fun writeBytesToShareOutboundFile(
        bytes: ByteArray,
        suffix: String,
    ): String {
        val dir = cacheDir.toPath() / SHARE_OUTBOUND_DIR_NAME
        fileSystem.createDirectories(dir)
        val path = dir / "share_${randomToken()}$suffix"
        fileSystem.write(path) { write(bytes) }
        return path.toString()
    }

    override suspend fun writeStream(
        path: String,
        data: Flow<ByteArray>
    ) {
        val p = path.toPath()
        p.parent?.let { fileSystem.createDirectories(it) }
        // okio's write{} block is non-suspend, so stream into a buffered sink instead.
        val sink = fileSystem.sink(p).buffer()
        try {
            data.collect { chunk -> sink.write(chunk) }
        } finally {
            sink.close()
        }
    }

    private fun randomToken(): String = Random.nextLong().toULong().toString(16)
}
