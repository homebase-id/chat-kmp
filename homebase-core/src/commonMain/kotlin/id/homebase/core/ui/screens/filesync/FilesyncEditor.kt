package id.homebase.core.ui.screens.filesync

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.files.FileVersionTag
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.UpdateFileByFileIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.core.filesync.FilesyncProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

private const val TAG = "FilesyncEditor"

/** [skipped]: items another device changed or deleted meanwhile, left as they are. */
class FilesyncWriteResult(val done: Int, val skipped: Int)

/** Restores and empties Recently deleted exactly as filesync's deleted.rs does, so every desktop agrees with the phone. */
class FilesyncEditor(
    private val repository: FilesyncRepository,
    private val driveUploadProvider: DriveUploadProvider,
    private val driveFileProvider: DriveFileHttpProvider,
) {
    /** Fires after any restore or empty, so a screen showing the drive can list it again. */
    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changed: SharedFlow<Unit> = _changed.asSharedFlow()

    // One write at a time: a restore picks names against the folder as it is, so two at once could pick the same one.
    private val writeLock = Mutex()

    /** Brings each of [nodeIds] back with the items its delete took along, and the removed folders above it first. */
    suspend fun restore(nodeIds: Set<Uuid>): FilesyncWriteResult = writeLock.withLock {
        val trash = FilesyncTrash(repository.listDeleted())
        val tops = nodeIds.mapNotNull { id -> trash.all.nodes[id]?.takeIf { it.removed } }
        // One already live was restored by an earlier tap or another device: nothing to do, nothing to report.
        val gone = (nodeIds - tops.map { it.nodeId }.toSet()).let { it.size - repository.findNodes(it, ArchivalStatus.None).nodes.size }
        val plan = tops.flatMap { trash.restorePlan(it) }.distinctBy { it.nodeId }
        val planned = plan.mapTo(mutableSetOf()) { it.nodeId }
        val outsideParents = plan.map { it.parentId }.filter { it !in planned }
        val liveParents = repository.findNodes(outsideParents, ArchivalStatus.None).nodes.keys

        val restored = mutableSetOf<Uuid>()
        val takenByFolder = mutableMapOf<Uuid, MutableSet<String>>()
        var skipped = gone
        for (node in plan) {
            val target = when (node.parentId) {
                in planned -> node.parentId.takeIf { it in restored }
                FilesyncProtocol.RootNodeId -> node.parentId
                // A live folder that went with a delete is back only once the removed folders it went with are.
                in trash.itemOf -> node.parentId.takeIf { parent ->
                    trash.chainOf(trash.all.nodes.getValue(parent)).all { !it.removed || it.nodeId in restored }
                }
                in liveParents -> node.parentId
                // Its folder was deleted for good: it comes back at the top level.
                else -> FilesyncProtocol.RootNodeId
            }
            if (target == null) {
                skipped++
                continue
            }
            val taken = takenByFolder.getOrPut(target) {
                repository.listFolder(target).nodes.values.mapTo(mutableSetOf()) { FilesyncNames.key(it.name) }
            }
            val name = FilesyncNames.restoredName(node.name, taken)
            val handle = trash.all.handles.getValue(node.nodeId)
            if (name == null || !handle.writable || !write(node.nodeId, handle, restoredContent(handle.content, name), target)) {
                skipped++
                continue
            }
            restored += node.nodeId
            taken += FilesyncNames.key(name)
        }
        if (restored.isNotEmpty()) _changed.tryEmit(Unit)
        FilesyncWriteResult(restored.size, skipped)
    }

    /** Deletes [items] for good, each with what was deleted with it; one changed since [seen] has its versionTag stays. */
    internal suspend fun deleteForGood(items: Set<Uuid>, seen: Map<Uuid, FilesyncHandle>): FilesyncWriteResult = writeLock.withLock {
        deleteListed(FilesyncTrash(repository.listDeleted()), items, seen)
    }

    private var purged = false

    /** The purge once a run, when the drive first opens; a failed one tries again the next time it opens. */
    suspend fun purgeOnce() {
        if (purged) return
        purged = true
        try {
            val result = purgeExpired()
            if (result.done + result.skipped > 0) Logger.i(TAG) { "purge: ${result.done} deleted for good, ${result.skipped} changed meanwhile" }
        } catch (e: Exception) {
            purged = false
            if (e is CancellationException) throw e
            Logger.w(e, TAG) { "purge failed" }
        }
    }

    // What every filesync client does (node schema, "The purge"), by the server's clock as the desktops count it.
    private suspend fun purgeExpired(): FilesyncWriteResult = writeLock.withLock {
        val (removed, readAtMs) = repository.listRemoved()
        val before = readAtMs - FilesyncProtocol.KeepDeleted.inWholeMilliseconds
        // Most runs find nothing old enough: only then is the walk below removed folders worth it.
        val expired = if (removed.nodes.values.none { it.updatedMs < before }) {
            FilesyncWriteResult(0, 0)
        } else {
            val trash = FilesyncTrash(repository.listDeleted(removed))
            deleteListed(trash, trash.removedBefore(before), seen = null, hard = true)
        }
        // A delete for good by hand leaves a tombstone, the feed's trace of it; once every desktop had 30 days to read it, it goes too.
        FilesyncWriteResult(expired.done + delete(repository.listTombstones(before), hard = true).size, expired.skipped)
    }

    /** Deletes the removed nodes of [items] in [now], a fresh listing, for good, each at its version: [hard] without a trace. */
    private suspend fun deleteListed(
        now: FilesyncTrash,
        items: Set<Uuid>,
        seen: Map<Uuid, FilesyncHandle>?,
        hard: Boolean = false,
    ): FilesyncWriteResult {
        val group = now.deletedWith(items)
        val unchanged = group.filterTo(mutableSetOf()) { id ->
            val handle = now.all.handles.getValue(id)
            handle.writable && (seen?.get(id)?.let { it.versionTag == handle.versionTag } ?: true)
        }
        val doomed = unchanged.map { now.all.nodes.getValue(it) }
            .filter { node -> node.removed && now.chainOf(node).all { it.nodeId in unchanged } }
            .associateByTo(mutableMapOf()) { it.nodeId }
        // Whatever stays, live or not, keeps the folders it is in.
        fun keep(node: FilesyncNode) = now.ancestorsOf(node).forEach { doomed.remove(it.nodeId) }
        now.all.nodes.values.filter { it.nodeId !in doomed }.forEach(::keep)
        val changed = items.count { it !in doomed }
        var deleted = 0
        var kept = 0
        var sent = false
        fun stays(node: FilesyncNode) {
            kept++
            keep(node)
        }
        try {
            for (pass in now.deleteOrder(doomed.values)) {
                val folders = pass.filter { it.isFolder && it.nodeId in doomed }.map { it.nodeId }
                if (folders.isNotEmpty()) {
                    repository.holdingLive(folders).forEach { id -> doomed.remove(id)?.let(::stays) }
                }
                val handles = pass.filter { it.nodeId in doomed }.associateWith { now.all.handles.getValue(it.nodeId) }
                val versioned = handles.values.mapNotNull { h -> h.versionTag?.let { FileVersionTag(h.fileId, it) } }
                sent = sent || versioned.isNotEmpty()
                val gone = delete(versioned, hard)
                for ((node, handle) in handles) {
                    if (handle.fileId in gone) deleted++ else stays(node)
                }
            }
        } finally {
            // A failed request may still have deleted some of its files.
            if (sent) _changed.tryEmit(Unit)
        }
        return FilesyncWriteResult(deleted, changed + kept)
    }

    /** The [files] gone now, [hard] without a trace; one written since its versionTag stays, and any other refusal fails. */
    private suspend fun delete(files: List<FileVersionTag>, hard: Boolean): Set<Uuid> =
        files.chunked(DriveFileHttpProvider.FILE_BATCH).flatMapTo(mutableSetOf()) { chunk ->
            val answers = if (hard) {
                driveFileProvider.hardDeleteFiles(FilesyncProtocol.DriveAlias, chunk)
            } else {
                driveFileProvider.softDeleteFiles(FilesyncProtocol.DriveAlias, chunk)
            }
            val byCode = answers.results.groupBy({ it.errorCode }, { it.fileId })
            val refused = byCode.keys - setOf(null, OdinClientErrorCode.VersionTagMismatch, OdinClientErrorCode.FileNotFound)
            check(refused.isEmpty()) { "delete refused: $refused" }
            byCode[null].orEmpty() + repository.goneForGood(byCode[OdinClientErrorCode.FileNotFound].orEmpty())
        }

    /** False when the node changed or went since it was read; that's another device's newer word, not a failure. */
    internal suspend fun write(nodeId: Uuid, handle: FilesyncHandle, content: JsonObject, parentId: Uuid): Boolean {
        val request = liveNodeUpdate(nodeId, handle, content, parentId)
        return try {
            driveUploadProvider.updateFileByFileId(request, onVersionConflict = { null }) != null
        } catch (e: NotFoundException) {
            if (!e.fromIdentity) throw e
            false
        } catch (e: ClientException) {
            if (e.errorCode != OdinClientErrorCode.FileNotFound) throw e
            false
        }.also { if (!it) Logger.i(TAG) { "node $nodeId changed elsewhere; left as it is" } }
    }
}

/** [content] (everything read, unknown keys too) as a live node in [parentId]: the node's own key with a new IV, guarded by its versionTag. */
internal suspend fun liveNodeUpdate(nodeId: Uuid, handle: FilesyncHandle, content: JsonObject, parentId: Uuid): UpdateFileByFileIdRequest {
    val keyHeader = KeyHeader(ByteArrayUtil.getRndByteArray(16), handle.keyHeader.aesKey)
    val metadata = filesyncMetadata(nodeId, parentId, handle.fileType, content.toString(), ArchivalStatus.None, handle.versionTag)
    return UpdateFileByFileIdRequest(
        driveId = FilesyncProtocol.DriveAlias,
        fileId = handle.fileId,
        keyHeader = keyHeader,
        instructions = FileUpdateInstructionSet(
            transferIv = ByteArrayUtil.getRndByteArray(16),
            locale = UpdateLocale.Local,
            recipients = emptyList(),
            manifest = UpdateManifest.build(),
        ),
        metadata = metadata.encryptContent(keyHeader),
    )
}

internal fun restoredContent(content: JsonObject, name: String) =
    JsonObject(content - REMOVAL_KEY + ("name" to JsonPrimitive(name)))

/** Runs [write] and turns its outcome into the one message the user sees; true when everything was written. */
internal suspend fun reportWrite(
    done: FilesyncNotice,
    failed: FilesyncError,
    send: suspend (FilesyncUiEvent) -> Unit,
    write: suspend () -> FilesyncWriteResult,
): Boolean {
    val result = try {
        write()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.e(e, TAG) { "$done failed" }
        null
    }
    send(
        when {
            result == null -> FilesyncUiEvent.Error(failed)
            result.skipped > 0 -> FilesyncUiEvent.Notice(FilesyncNotice.ChangedElsewhere)
            else -> FilesyncUiEvent.Notice(done)
        }
    )
    return result != null && result.skipped == 0
}
