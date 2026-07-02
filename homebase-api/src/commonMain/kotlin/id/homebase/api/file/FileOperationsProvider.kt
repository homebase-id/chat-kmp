package id.homebase.api.file

import io.ktor.client.request.forms.InputProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf

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

    /**
     * Whether [path] currently resolves to a readable upload source. Probed at the
     * encryption-on-send boundary so a swept/evicted temp (or a revoked
     * `content://`/`ph://` permission) fails soft — see [SourceUnavailableException]
     * — instead of throwing an uncaught read error and enqueuing a doomed outbox row.
     *
     * The default probes via [getFileSize] (size > 0 ⇒ present). Platforms whose
     * sources include URIs [getFileSize] cannot size — notably iOS `ph://` Photos
     * assets, which `getFileSize` reports as 0 — MUST override with a real existence
     * check, or every such source would be misreported as missing.
     */
    suspend fun sourceExists(path: String): Boolean = getFileSize(path) > 0L

    /**
     * Write a RAW, pre-encryption source temp into `<cacheDir>/upload-temp/` (see
     * [CacheAudit.UPLOAD_TEMP_DIR_NAME]). Disposable: the CacheSweeper reaps this dir on every
     * startup / "Clear caches", so a leaked temp self-heals and can't grow — a source that's gone
     * at send time just fails soft (re-pick). Use this for the plaintext inputs to the pipeline.
     */
    suspend fun writeBytesToTempFile(
        bytes: ByteArray,
        prefix: String,
        suffix: String
    ): String

    /**
     * Absolute path of the DURABLE outbox staging directory (#842). Unlike
     * [getCacheDirectory] this location is never reclaimed by the OS under storage
     * pressure and sits outside the CacheSweeper's reach — every file here is
     * referenced by an outbox row until the send completes (long-lived when
     * offline) and is reaped ONLY along the outbox lifecycle (send success /
     * permanent drop / logout / idle orphan reap). See [OUTBOX_STAGING_DIR_NAME].
     *
     * Platform actuals: JVM app-data dir, Android `noBackupFilesDir`, iOS
     * Application Support (backup-excluded). The default — used by web — stays at
     * `<cacheDir>/outbox-temp`: the wasm FS is a RAM-only FakeFileSystem and the
     * sql.js DB is in-memory, so outbox rows don't survive a refresh either; true
     * web durability is deferred until web gets persistent storage.
     */
    fun getOutboxStagingDirectory(): String =
        getCacheDirectory().trimEnd('/') + "/" + CacheAudit.OUTBOX_TEMP_DIR_NAME

    /**
     * Reserve a unique writable path inside [getOutboxStagingDirectory] (creating
     * the dir) WITHOUT writing bytes — the seam for stream writers
     * ([writeStream] of an encrypting Flow), which must produce their output
     * directly in staging instead of writing cache scratch and copying.
     */
    suspend fun createOutboxStagingPath(prefix: String, suffix: String): String =
        createStagingPathIn(getOutboxStagingDirectory(), prefix, suffix)

    /**
     * Move an encrypted, ready-to-transmit file OR directory (e.g. an
     * `hls_<uuid>/` tree) into [getOutboxStagingDirectory] and return its new
     * absolute path. Rename-first with a recursive copy+delete fallback for
     * cross-filesystem moves — see [promoteIntoStaging].
     */
    suspend fun promoteToOutboxStaging(path: String): String =
        promoteIntoStaging(path, getOutboxStagingDirectory())

    /**
     * Write an ENCRYPTED, ready-to-transmit payload into the durable outbox
     * staging dir ([getOutboxStagingDirectory]) and return its absolute path.
     * Each staged file is referenced by an outbox row and reaped only along the
     * outbox lifecycle — never by the CacheSweeper or OS cache reclaim.
     */
    suspend fun writeBytesToOutboxTempFile(
        bytes: ByteArray,
        prefix: String,
        suffix: String
    ): String {
        val path = createOutboxStagingPath(prefix, suffix)
        writeStream(path, flowOf(bytes))
        return path
    }

    /**
     * Write [bytes] to a sequestered subdirectory `<cacheDir>/share_outbound/`
     * (see [SHARE_OUTBOUND_DIR_NAME]) using a `share_<random>$suffix` filename
     * and return the absolute path. Used **only** by the chat "Share to other
     * app" flow, which decrypts a Homebase payload and hands the resulting
     * cleartext file to `Intent.ACTION_SEND` via Android `FileProvider`.
     *
     * The subdir lets [sweepShareOutbound] reap every cleartext share temp
     * as a single unit on cold start and on app foreground — bounding the
     * on-disk lifetime of decrypted Homebase content.
     */
    suspend fun writeBytesToShareOutboundFile(
        bytes: ByteArray,
        suffix: String,
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

/**
 * Resolve [path] (which may be an Android `content://` URI) to a real
 * filesystem path, run [block] with it, and reap the resolved copy afterwards.
 *
 * [resolveToFilePath] COPIES a `content://` pick into cacheDir as
 * `resolved_*.<ext>` — a plaintext copy the full size of the source. Callers
 * that picked through the gallery (video send, vault upload) must delete that
 * copy once they've consumed it, or it lingers in cacheDir until the next
 * cold-start [CacheSweeper] run (observed: a 125 MB send leaving a 125 MB
 * `resolved_*.mp4`). This is the one shared place that pairs the resolve with
 * its delete, so no call site can forget — and on platforms where
 * `resolveToFilePath` is a no-op the path is unchanged and nothing is deleted.
 *
 * The reap runs in `finally`, so it also covers the failure path; the startup
 * sweep stays as the backstop if even that delete fails.
 */
suspend fun <T> FileOperationsProvider.withResolvedFile(
    path: String,
    block: suspend (resolvedPath: String) -> T,
): T {
    val resolved = resolveToFilePath(path)
    return try {
        block(resolved)
    } finally {
        if (resolved != path) deleteTempFile(resolved)
    }
}

/**
 * List form of [withResolvedFile]: resolve every path in [paths], run [block]
 * with the resolved paths (positionally aligned with [paths]), and reap each
 * resolved copy afterwards. Used by callers that pick several files at once
 * (vault multi-upload). Same reap rule per entry — a path that resolved to
 * itself (no copy made) is left alone.
 */
suspend fun <T> FileOperationsProvider.withResolvedFiles(
    paths: List<String>,
    block: suspend (resolvedPaths: List<String>) -> T,
): T {
    val resolved = paths.map { resolveToFilePath(it) }
    return try {
        block(resolved)
    } finally {
        for (i in paths.indices) {
            if (resolved[i] != paths[i]) deleteTempFile(resolved[i])
        }
    }
}
