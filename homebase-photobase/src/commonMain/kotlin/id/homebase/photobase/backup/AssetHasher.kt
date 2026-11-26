package id.homebase.photobase.backup

import kotlin.time.Duration
import id.homebase.api.crypto.Blake3Hasher
import id.homebase.api.crypto.PhotoUniqueId
import id.homebase.api.file.FileOperationsProvider
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlin.uuid.Uuid

class HashedAsset(val contentDigests: List<ByteArray>, val sizeBytes: Long = 0) {
    val uniqueId: Uuid = PhotoUniqueId.fromContentDigests(contentDigests)

    @OptIn(ExperimentalStdlibApi::class)
    val contentHash: String = contentDigests.joinToString(CONTENT_HASH_SEPARATOR) { it.toHexString() }

    companion object {
        const val CONTENT_HASH_SEPARATOR = ":"
    }
}

/** Plaintext BLAKE3 of the original bytes, before anything is encrypted or written. */
class AssetHasher(
    /** Null for one that only hashes files it's given, as a pick to add to someone's album is: no device library. */
    private val exports: HashedExports?,
    private val fileOps: FileOperationsProvider,
) {
    private val library get() = checkNotNull(exports) { "no device library to export from" }

    suspend fun hash(assetId: String, mediaType: BackupMediaType): HashedAsset {
        val exports = library
        val exported = exports.export(assetId, mediaType)
        try {
            val hashed = timed(BackupStage.Hash) {
                var size = 0L
                HashedAsset(exported.parts.map { part -> digest(part.path) { size += it.size } }, size)
            }
            exports.keep(assetId, mediaType, exported)
            return hashed
        } catch (e: Throwable) {
            exported.deleteTemporaryParts(fileOps)
            throw e
        }
    }

    suspend fun awaitExportRoom(limit: Duration): Boolean = library.awaitRoom(limit)

    suspend fun forget(assetId: String) = library.forget(assetId)

    suspend fun forgetAll() = library.forgetAll()

    /** Whether [part] still holds the bytes hashed as [expected]; a hashed copy is those very files. */
    suspend fun matches(exported: ExportedAsset, part: ExportedPart, expected: ByteArray): Boolean =
        exported.isHashedCopy || digest(part.path).contentEquals(expected)

    suspend fun digest(path: String, onChunk: (ByteArray) -> Unit = {}): ByteArray {
        val hasher = Blake3Hasher()
        fileOps.readFileAsFlow(path).onEach {
            hasher.update(it)
            onChunk(it)
        }.collect()
        return hasher.digest()
    }
}

suspend fun ExportedAsset.deleteTemporaryParts(fileOps: FileOperationsProvider) {
    for (part in parts) {
        if (part.isTemporary) runCatching { fileOps.deleteTempFile(part.path) }
    }
}
