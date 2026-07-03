package id.homebase.api.file

import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import okio.BufferedSource
import okio.FileSystem
import okio.buffer
import okio.use
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

    /**
     * Lazy, CHUNKED multipart upload source (#947). Previously this read the whole
     * file eagerly at `openFileInput` time and replayed the captured array on every
     * block invocation. Now each ktor block invocation reopens the file (the block
     * is re-invoked on ktor retries and outbox re-drives) and streams 64 KB at a
     * time. `size` stays populated from metadata — it feeds the multipart
     * Content-Length and is pinned by OkioFileOperationsProviderTest.
     */
    override fun openFileInput(path: String): InputProvider =
        InputProvider(size = fileSystem.metadataOrNull(path.toPath())?.size) {
            OkioBackedRawSource(fileSystem.source(path.toPath()).buffer()).buffered()
        }

    override suspend fun readFileBytes(path: String): ByteArray =
        fileSystem.read(path.toPath()) { readByteArray() }

    // Real chunked streaming (#842) — the interface default emits the whole file as
    // ONE chunk, defeating streamed encryption's bounded-memory point.
    override fun readFileAsFlow(path: String, chunkSize: Int): Flow<ByteArray> = flow {
        fileSystem.source(path.toPath()).buffer().use { source ->
            val buf = ByteArray(chunkSize)
            while (true) {
                val read = source.read(buf, 0, chunkSize)
                if (read == -1) break
                if (read > 0) emit(buf.copyOf(read))
            }
        }
    }

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

    override suspend fun createShareOutboundPath(suffix: String): String =
        createStagingPathIn("$cacheDir/$SHARE_OUTBOUND_DIR_NAME", "share_", suffix, fileSystem)

    override suspend fun createUploadTempPath(prefix: String, suffix: String): String =
        createStagingPathIn("$cacheDir/${CacheAudit.UPLOAD_TEMP_DIR_NAME}", prefix, suffix, fileSystem)

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

/**
 * A [kotlinx.io.RawSource] over an okio [BufferedSource], read in [chunkSize] steps —
 * the lazy backing for [OkioFileOperationsProvider.openFileInput] (#947). Holds at
 * most one chunk in memory. No okio↔kotlinx-io bridge exists in the repo; this small
 * adapter is that bridge for the read direction.
 */
private class OkioBackedRawSource(
    private val source: BufferedSource,
    private val chunkSize: Int = FileOperationsProvider.DEFAULT_HEADER_BYTES,
) : RawSource {
    private val buf = ByteArray(chunkSize)

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        require(byteCount >= 0L) { "byteCount must be non-negative: $byteCount" }
        if (byteCount == 0L) return 0L
        val read = source.read(buf, 0, minOf(byteCount, chunkSize.toLong()).toInt())
        if (read == -1) return -1L
        sink.write(buf, startIndex = 0, endIndex = read)
        return read.toLong()
    }

    override fun close() = source.close()
}
