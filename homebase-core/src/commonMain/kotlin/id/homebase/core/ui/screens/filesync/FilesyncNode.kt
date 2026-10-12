package id.homebase.core.ui.screens.filesync

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.PayloadHash
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.filesync.FilesyncProtocol
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.math.roundToLong
import kotlin.uuid.Uuid

/** A node in filesync's Recently deleted: removed at [atMs] by the delete of [root] (itself, or a folder it was in). */
@Immutable
data class FilesyncRemoval(val atMs: Long, val root: Uuid)

@Immutable
data class FilesyncNode(
    val nodeId: Uuid,
    val parentId: Uuid,
    val isFolder: Boolean,
    val name: String,
    val modifiedMs: Long?,
    val sizeBytes: Long?,
    val addedMs: Long = 0,
    val removal: FilesyncRemoval? = null,
)

/** What a download of a file node needs. */
class FilesyncPayload(
    val fileId: Uuid,
    val payloadKey: KeyHeader,
    val hash: PayloadHash,
)

/** What a write to a node needs; kept out of [FilesyncNode] so UI state carries no keys. [content] is kept whole so a write loses no key. */
class FilesyncHandle(
    val fileId: Uuid,
    val versionTag: Uuid?,
    val fileType: Int,
    val keyHeader: KeyHeader,
    val content: JsonObject,
    val payload: FilesyncPayload?,
)

/** The typed part of a node's encrypted appData.content; [mtime] and [size] are for files only. */
@Serializable
internal data class FilesyncNodeContent(
    val v: Int = 0,
    val name: String? = null,
    val mtime: Long? = null,
    val size: Long? = null,
)

internal const val REMOVAL_KEY = "rm"

private const val TAG = "FilesyncNode"

internal fun HomebaseFile.toFilesyncNode(): Pair<FilesyncNode, FilesyncHandle>? {
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
    val raw = runCatching { OdinSystemSerializer.json.parseToJsonElement(appData.content.orEmpty()).jsonObject }.getOrNull()
    val content = raw?.let { runCatching { OdinSystemSerializer.json.decodeFromJsonElement(FilesyncNodeContent.serializer(), it) }.getOrNull() }
    val name = content?.name?.takeIf { content.v >= 1 && FilesyncNames.isValid(it) }
    // Without its size a file's padded stored size would be taken for it, and every download would fail its hash.
    if (raw == null || name == null || (!isFolder && content.size == null)) {
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

    val node = FilesyncNode(
        nodeId = nodeId,
        parentId = appData.groupId ?: FilesyncProtocol.RootNodeId,
        isFolder = isFolder,
        name = name,
        modifiedMs = content.mtime,
        sizeBytes = content.size,
        addedMs = fileMetadata.created.milliseconds,
        removal = removalOf(appData.archivalStatus, raw, nodeId, fileMetadata.updated.milliseconds),
    )
    val fileType = if (isFolder) FilesyncProtocol.FolderFileType else FilesyncProtocol.FileFileType
    return node to FilesyncHandle(fileId, fileMetadata.versionTag, fileType, keyHeader, raw, payload)
}

/** Only status 2 removes; a node removed by another app has no readable `rm` and counts as removed at [updatedMs], on its own. */
internal fun removalOf(status: ArchivalStatus?, content: JsonObject, nodeId: Uuid, updatedMs: Long): FilesyncRemoval? {
    if (status != ArchivalStatus.Removed) return null
    val rm = content[REMOVAL_KEY] as? JsonObject
    val at = (rm?.get("at") as? JsonPrimitive)?.takeIf { !it.isString }?.let { p ->
        p.longOrNull ?: p.doubleOrNull?.takeIf { it.isFinite() }?.roundToLong()
    }
    val root = (rm?.get("root") as? JsonPrimitive)?.takeIf { it.isString }?.let { runCatching { Uuid.parse(it.content) }.getOrNull() }
    return if (at != null && root != null) FilesyncRemoval(at, root) else FilesyncRemoval(updatedMs, nodeId)
}
