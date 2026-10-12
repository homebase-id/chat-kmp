package id.homebase.core.ui.screens.filesync

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.PayloadHash
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.filesync.FilesyncProtocol
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.uuid.Uuid

@Immutable
data class FilesyncNode(
    val nodeId: Uuid,
    val isFolder: Boolean,
    val name: String,
    val modifiedMs: Long?,
    val sizeBytes: Long?,
)

/** What a download of a file node needs; kept out of [FilesyncNode] so UI state carries no keys. */
class FilesyncPayload(
    val fileId: Uuid,
    val payloadKey: KeyHeader,
    val hash: PayloadHash,
)

/** The encrypted appData.content of a node; [mtime] and [size] are for files only. */
@Serializable
internal data class FilesyncNodeContent(
    val v: Int = 0,
    val name: String? = null,
    val mtime: Long? = null,
    val size: Long? = null,
)

private const val TAG = "FilesyncNode"

internal fun HomebaseFile.toFilesyncNode(): Pair<FilesyncNode, FilesyncPayload?>? {
    val appData = fileMetadata.appData
    val isFolder = when (appData.fileType) {
        FilesyncProtocol.FolderFileType -> true
        FilesyncProtocol.FileFileType -> false
        else -> return null
    }
    val nodeId = appData.uniqueId ?: return null
    if (!fileMetadata.isEncrypted) {
        Logger.w(TAG) { "skipping node $nodeId: not encrypted" }
        return null
    }
    val content = runCatching {
        OdinSystemSerializer.json.decodeFromString<FilesyncNodeContent>(appData.content.orEmpty())
    }.getOrNull()
    val name = content?.name?.takeIf { content.v >= 1 && FilesyncNames.isValid(it) }
    // Without its size a file's padded stored size would be taken for it, and every download would fail its hash.
    if (name == null || (!isFolder && content.size == null)) {
        Logger.w(TAG) { "skipping node $nodeId: unreadable content" }
        return null
    }

    val payload = if (isFolder) null else {
        val descriptor = fileMetadata.getPayloadDescriptor(FilesyncProtocol.PayloadKey)
        val iv = descriptor?.iv?.let { runCatching { Base64.decode(it) }.getOrNull() }
        val hash = descriptor?.hash
        if (iv == null || hash == null) {
            Logger.w(TAG) { "skipping file $nodeId: no hashed ${FilesyncProtocol.PayloadKey} payload" }
            return null
        }
        FilesyncPayload(fileId, KeyHeader(iv, keyHeader.aesKey), hash)
    }

    return FilesyncNode(
        nodeId = nodeId,
        isFolder = isFolder,
        name = name,
        modifiedMs = content.mtime,
        sizeBytes = content.size,
    ) to payload
}
