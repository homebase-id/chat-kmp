package id.homebase.core.ui.screens.filesync

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.FileVersionTag
import id.homebase.api.client.drives.files.PayloadHash
import id.homebase.api.client.drives.files.PayloadHashAlgorithm
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.filesync.FilesyncProtocol
import id.homebase.core.filesync.FilesyncRoleFolder
import id.homebase.core.util.FileKind
import id.homebase.core.util.fileKindOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlin.io.encoding.Base64
import kotlin.math.roundToLong
import kotlin.uuid.Uuid

/** A Finder tag; [colour] is Finder's 1 grey … 7 orange, or null for none. */
@Immutable
data class FilesyncTag(val name: String, val colour: Int?)

@Immutable
data class FilesyncNode(
    val nodeId: Uuid,
    val parentId: Uuid,
    val isFolder: Boolean,
    val name: String,
    val modifiedMs: Long?,
    val sizeBytes: Long?,
    val addedMs: Long = 0,
    val removed: Boolean = false,
    val createdMs: Long? = null,
    val executable: Boolean = false,
    val tags: List<FilesyncTag> = emptyList(),
    val uploadedMs: Long? = null,
    val hashAlgorithm: PayloadHashAlgorithm? = null,
    val updatedMs: Long = 0,
) {
    // Once per node: search and sorting compare these on every keystroke, and neither is free on the web.
    internal val nameKey: String = FilesyncNames.key(name)
    internal val kind: FileKind? = if (isFolder) null else fileKindOf(null, name)
    internal val role: FilesyncRoleFolder? = if (isFolder) FilesyncRoleFolder.of(nodeId) else null
    internal val notOnComputers: Boolean = nameKey == FilesyncNames.stateFolderKey && FilesyncNames.isSyncTop(parentId)

    /** Set while it's in Recently deleted: nothing writes to a removed node, so its last write is its removal. */
    val removedMs: Long? get() = updatedMs.takeIf { removed }
}

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
    version: Int = CONTENT_VERSION,
) {
    val fileVersionTag: FileVersionTag? get() = versionTag?.let { FileVersionTag(fileId, it) }

    /** A node from a newer content version is shown but never changed. */
    val writable = version == CONTENT_VERSION
}

/** The typed part of a node's encrypted appData.content; [mtime] and [size] are for files only. */
@Serializable
internal data class FilesyncNodeContent(
    val v: Int = 0,
    val name: String? = null,
    val mtime: Long? = null,
    val size: Long? = null,
)

// filesync wrote it to removed nodes before 2026-10-15; it means nothing now, and a write leaves it out.
internal const val REMOVAL_KEY = "rm"

internal const val CONTENT_VERSION = 1

private const val TAG = "FilesyncNode"

internal fun HomebaseFile.toFilesyncNode(): Pair<FilesyncNode, FilesyncHandle>? {
    val appData = fileMetadata.appData
    val isFolder = when (appData.fileType) {
        FilesyncProtocol.FolderFileType -> true
        FilesyncProtocol.FileFileType -> false
        else -> return null
    }
    val nodeId = appData.uniqueId ?: return null
    if (!serverFileIsEncrypted) {
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

    val descriptor = if (isFolder) null else fileMetadata.getPayloadDescriptor(FilesyncProtocol.PayloadKey)
    val payload = if (isFolder) null else {
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
        removed = appData.archivalStatus == ArchivalStatus.Removed,
        createdMs = epochMs(raw["ct"]),
        executable = (raw["x"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true,
        tags = tagsOf(raw),
        uploadedMs = descriptor?.lastModified,
        hashAlgorithm = payload?.hash?.knownAlgorithm,
        updatedMs = fileMetadata.updated.milliseconds,
    )
    val fileType = if (isFolder) FilesyncProtocol.FolderFileType else FilesyncProtocol.FileFileType
    return node to FilesyncHandle(fileId, fileMetadata.versionTag, fileType, keyHeader, raw, payload, content.v)
}

/** A time in Unix ms as filesync writes it: an int, or a finite float rounded; anything else is none. */
private fun epochMs(element: JsonElement?): Long? =
    (element as? JsonPrimitive)?.takeIf { !it.isString }?.let { p ->
        p.longOrNull ?: p.doubleOrNull?.takeIf { it.isFinite() }?.roundToLong()
    }

/** Entries without a string name are skipped and an unknown colour is none, as filesync reads them; one per name, sorted by name. */
private fun tagsOf(content: JsonObject): List<FilesyncTag> =
    (content["tags"] as? JsonArray).orEmpty()
        .mapNotNull { entry ->
            val tag = entry as? JsonObject ?: return@mapNotNull null
            val name = (tag["n"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content ?: return@mapNotNull null
            val colour = (tag["c"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { FinderTagColors.of(it) != null }
            FilesyncTag(name, colour)
        }
        .distinctSorted()
