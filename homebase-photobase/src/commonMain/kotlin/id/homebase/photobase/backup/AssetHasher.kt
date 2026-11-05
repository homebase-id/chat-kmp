package id.homebase.photobase.backup

import id.homebase.api.crypto.Blake3Hasher
import id.homebase.api.file.FileOperationsProvider
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlin.uuid.Uuid

class HashedAsset(val contentDigests: List<ByteArray>) {
    val uniqueId: Uuid = PhotoUniqueId.fromContentDigests(contentDigests)

    @OptIn(ExperimentalStdlibApi::class)
    val contentHash: String = contentDigests.joinToString(CONTENT_HASH_SEPARATOR) { it.toHexString() }

    companion object {
        const val CONTENT_HASH_SEPARATOR = ":"
    }
}

/** Pass 1 of §3.2: plaintext BLAKE3 of the original bytes, before anything is encrypted or written. */
class AssetHasher(
    private val source: DeviceChangeSource,
    private val fileOps: FileOperationsProvider,
) {
    suspend fun hash(assetId: String, mediaType: BackupMediaType): HashedAsset {
        val exported = source.export(assetId, mediaType)
        try {
            return HashedAsset(exported.parts.map { digest(it.path) })
        } finally {
            exported.deleteTemporaryParts(fileOps)
        }
    }

    suspend fun digest(path: String): ByteArray {
        val hasher = Blake3Hasher()
        fileOps.readFileAsFlow(path).onEach { hasher.update(it) }.collect()
        return hasher.digest()
    }
}

suspend fun ExportedAsset.deleteTemporaryParts(fileOps: FileOperationsProvider) {
    for (part in parts) {
        if (part.isTemporary) runCatching { fileOps.deleteTempFile(part.path) }
    }
}
