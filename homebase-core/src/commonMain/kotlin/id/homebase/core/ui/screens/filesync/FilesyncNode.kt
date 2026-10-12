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
    val fileId: Uuid,
    val isFolder: Boolean,
    val name: String,
    val modifiedMs: Long?,
    val sizeBytes: Long?,
    /** Written by a newer schema; shown, never changed. */
    val readOnly: Boolean,
)

/** What a download of a file node needs; kept out of [FilesyncNode] so UI state carries no keys. */
class FilesyncPayload(
    val fileId: Uuid,
    val payloadKey: KeyHeader,
    val hash: PayloadHash?,
)

@Serializable
private data class FilesyncNodeContent(
    val v: Int = 0,
    val name: String? = null,
    val mtime: Long? = null,
    val size: Long? = null,
)

private const val TAG = "FilesyncNode"

/** Null for anything that isn't a readable node: another file type, no id, or content that won't decrypt or parse. */
internal fun HomebaseFile.toFilesyncNode(): Pair<FilesyncNode, FilesyncPayload?>? {
    val appData = fileMetadata.appData
    val isFolder = when (appData.fileType) {
        FilesyncProtocol.FolderFileType -> true
        FilesyncProtocol.FileFileType -> false
        else -> return null
    }
    val nodeId = appData.uniqueId ?: return null
    val content = runCatching {
        OdinSystemSerializer.json.decodeFromString<FilesyncNodeContent>(appData.content.orEmpty())
    }.getOrNull()
    val name = content?.name?.takeIf { it.isNotBlank() && content.v >= 1 }
    if (name == null) {
        Logger.w(TAG) { "skipping node $nodeId: unreadable content" }
        return null
    }

    val payload = if (isFolder) null else {
        val descriptor = fileMetadata.getPayloadDescriptor(FilesyncProtocol.PayloadKey)
        val iv = descriptor?.iv?.let { runCatching { Base64.decode(it) }.getOrNull() }
        if (descriptor == null || iv == null) {
            Logger.w(TAG) { "skipping file $nodeId: no ${FilesyncProtocol.PayloadKey} payload" }
            return null
        }
        FilesyncPayload(fileId, KeyHeader(iv, keyHeader.aesKey), descriptor.hash)
    }

    return FilesyncNode(
        nodeId = nodeId,
        fileId = fileId,
        isFolder = isFolder,
        name = name,
        modifiedMs = content.mtime,
        sizeBytes = content.size,
        readOnly = content.v > FilesyncProtocol.ContentVersion,
    ) to payload
}
