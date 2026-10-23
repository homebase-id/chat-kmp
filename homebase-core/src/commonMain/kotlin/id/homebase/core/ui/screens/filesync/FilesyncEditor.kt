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

// What another device's newer word looks like in a per-file answer: the node is left as it is.
private val ChangedElsewhere = setOf(OdinClientErrorCode.VersionTagMismatch, OdinClientErrorCode.FileNotFound, OdinClientErrorCode.CannotUpdateNonActiveFile)

/**
 * [skipped]: items another device changed or deleted meanwhile, left as they are. [recovered]: restored where no listing
 * shows them until a computer moves them into Recovered files.
 */
class FilesyncWriteResult(val done: Int, val skipped: Int, val recovered: Int = 0)

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
        val chains = mutableMapOf<Uuid, List<FilesyncNode>>()
        fun chainAbove(node: FilesyncNode) = chains.getOrPut(node.parentId) { trash.all.nodes[node.parentId]?.let(trash::chainOf).orEmpty() }
        // A node waits for the planned folders it comes back into, through live folders too: a level after the last of them.
        val level = mutableMapOf<Uuid, Int>()
        for (node in plan) level[node.nodeId] = chainAbove(node).mapNotNull { level[it.nodeId] }.maxOrNull()?.plus(1) ?: 0
        val liveParents = repository.findNodes(plan.map { it.parentId }.filter { it !in level }, ArchivalStatus.None).nodes.keys

        val restored = mutableSetOf<Uuid>()
        // Folders of the plan deleted for good while it ran.
        val wentForGood = mutableSetOf<Uuid>()
        val intoRecovered = mutableSetOf<Uuid>()
        val takenByFolder = mutableMapOf<Uuid, MutableSet<String>>()
        suspend fun taken(folder: Uuid) = takenByFolder.getOrPut(folder) { repository.takenNames(folder) }
        var skipped = gone
        var sent = false
        try {
            for (wave in plan.sortedBy { level.getValue(it.nodeId) }.groupBy { level.getValue(it.nodeId) }.values) {
                // Back under its own name in its own folder, a node needs only its archivalStatus set, many in one request,
                // sent before the others pick names, so one a refused node leaves is free for them.
                val batch = mutableListOf<Pair<FileVersionTag, FilesyncNode>>()
                val rewrites = mutableListOf<FilesyncNode>()
                // By fileId: failed folders whose header tells whether they went for good.
                val maybeGone = mutableMapOf<Uuid, Uuid>()
                for (node in wave) {
                    // True when its folder went for good: it comes back as it was, without a name check (null: it stays).
                    val orphan = when (node.parentId) {
                        in restored -> false
                        in wentForGood -> true
                        in level -> null
                        FilesyncProtocol.RootNodeId -> false
                        // A live folder that went with a delete is back only once the removed folders it went with are.
                        in trash.itemOf -> chainAbove(node).let { chain ->
                            if (chain.any { it.nodeId in wentForGood }) intoRecovered += node.nodeId
                            false.takeIf { chain.all { !it.removed || it.nodeId in restored || it.nodeId in wentForGood } }
                        }
                        in liveParents -> false
                        else -> true
                    }
                    if (orphan == true) intoRecovered += node.nodeId
                    val handle = trash.all.handles.getValue(node.nodeId)
                    val version = handle.fileVersionTag
                    when {
                        orphan == null || !handle.writable -> skipped++
                        version != null && (orphan || taken(node.parentId).add(node.nameKey)) -> batch += version to node
                        else -> rewrites += node
                    }
                }
                for (chunk in batch.chunked(DriveFileHttpProvider.FILE_BATCH)) {
                    sent = true
                    val nodes = chunk.associate { (file, node) -> file.fileId to node }
                    val refused = mutableSetOf<OdinClientErrorCode>()
                    for (answer in driveFileProvider.updateArchivalStatus(FilesyncProtocol.DriveAlias, ArchivalStatus.None, chunk.map { it.first }).results) {
                        val node = nodes.getValue(answer.fileId)
                        when (val code = answer.errorCode) {
                            null -> restored += node.nodeId
                            in ChangedElsewhere -> {
                                skipped++
                                takenByFolder[node.parentId]?.remove(node.nameKey)
                                if (node.isFolder && code == OdinClientErrorCode.FileNotFound) maybeGone[answer.fileId] = node.nodeId
                                if (node.isFolder && code == OdinClientErrorCode.CannotUpdateNonActiveFile) wentForGood += node.nodeId
                                Logger.i(tag = TAG) { "node ${node.nodeId} changed elsewhere; left as it is" }
                            }
                            else -> refused += code
                        }
                    }
                    check(refused.isEmpty()) { "restore refused: $refused" }
                }
                for (node in rewrites) {
                    val taken = taken(node.parentId)
                    val name = FilesyncNames.restoredName(node.name, taken)
                    if (name == null) {
                        skipped++
                        continue
                    }
                    sent = true
                    val handle = trash.all.handles.getValue(node.nodeId)
                    if (write(node.nodeId, handle, restoredContent(handle.content, name), node.parentId)) {
                        restored += node.nodeId
                        taken += FilesyncNames.key(name)
                    } else {
                        skipped++
                        if (node.isFolder) maybeGone[handle.fileId] = node.nodeId
                    }
                }
                repository.goneForGood(maybeGone.keys.toList()).mapTo(wentForGood) { maybeGone.getValue(it) }
            }
        } finally {
            // A failed request may still have restored some of its files.
            if (sent) _changed.tryEmit(Unit)
        }
        FilesyncWriteResult(restored.size, skipped, recovered = (intoRecovered intersect restored).size)
    }

    /** Empties [items], each with everything removed below it; one not in [seen], or changed since, stays. */
    internal suspend fun deleteForGood(items: Set<Uuid>, seen: Map<Uuid, FilesyncHandle>): FilesyncWriteResult = writeLock.withLock {
        val trash = FilesyncTrash(repository.listDeleted())
        deleteListed(trash, items, trash.inside(items) + trash.deletedWith(items), seen)
    }

    private var purged = false

    /** The purge once a run, when the drive first opens; a failed one tries again the next time it opens. */
    suspend fun purgeOnce() {
        if (purged) return
        purged = true
        try {
            val result = purgeExpired()
            if (result.done + result.skipped > 0) Logger.i(tag = TAG) { "purge: ${result.done} deleted for good, ${result.skipped} changed meanwhile" }
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
        // Each part runs whatever became of the other; the first failure fails the purge at the end.
        var failed: Exception? = null
        val expired = try {
            // Most runs find nothing old enough: only then is the walk below removed folders worth it.
            if (removed.nodes.values.none { it.updatedMs < before }) {
                FilesyncWriteResult(0, 0)
            } else {
                val trash = FilesyncTrash(repository.listDeleted(removed))
                val items = trash.removedBefore(before)
                deleteListed(trash, items, trash.deletedWith(items), seen = null, hard = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(e, TAG) { "purge of expired items failed" }
            failed = e
            FilesyncWriteResult(0, 0)
        }
        // A delete for good by hand leaves a tombstone, the feed's trace of it; once every desktop had 30 days to read it, it goes too.
        val refused = mutableSetOf<OdinClientErrorCode>()
        val tombstones = delete(repository.listTombstones(before), hard = true, refused).size
        failed?.let { throw it }
        check(refused.isEmpty()) { "tombstone delete refused: $refused" }
        FilesyncWriteResult(expired.done + tombstones, expired.skipped)
    }

    /**
     * Deletes the removed nodes of [group], what [items] take in [now], a fresh listing, for good, each at its version:
     * [hard] without a trace. One the server refuses for another reason stays, and the delete fails once the rest went.
     */
    private suspend fun deleteListed(
        now: FilesyncTrash,
        items: Set<Uuid>,
        group: Set<Uuid>,
        seen: Map<Uuid, FilesyncHandle>?,
        hard: Boolean = false,
    ): FilesyncWriteResult {
        val unchanged = group.filterTo(mutableSetOf()) { id ->
            val handle = now.all.handles.getValue(id)
            handle.writable && (seen == null || seen[id]?.let { it.versionTag == handle.versionTag } == true)
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
        val refused = mutableSetOf<OdinClientErrorCode>()
        fun stays(node: FilesyncNode) {
            kept++
            keep(node)
        }
        // Rows going in this delete don't hold their folders, else a cycle of removed folders, all in one pass, never goes.
        val going = doomed.keys.mapNotNull { now.all.handles.getValue(it).fileVersionTag }.toSet()
        try {
            for (pass in now.deleteOrder(doomed.values)) {
                val folders = pass.filter { it.isFolder && it.nodeId in doomed }.map { it.nodeId }
                if (folders.isNotEmpty()) {
                    repository.holding(folders, except = going).forEach { id -> doomed.remove(id)?.let(::stays) }
                }
                val handles = pass.filter { it.nodeId in doomed }.associateWith { now.all.handles.getValue(it.nodeId) }
                val versioned = handles.values.mapNotNull { it.fileVersionTag }
                sent = sent || versioned.isNotEmpty()
                val gone = delete(versioned, hard, refused)
                for ((node, handle) in handles) {
                    if (handle.fileId in gone) deleted++ else stays(node)
                }
            }
            check(refused.isEmpty()) { "delete refused: $refused" }
        } finally {
            // A failed request may still have deleted some of its files.
            if (sent) _changed.tryEmit(Unit)
        }
        return FilesyncWriteResult(deleted, changed + kept)
    }

    /** The [files] gone now, [hard] without a trace; one written since its versionTag stays, as does one answered with a code put in [refused]. */
    private suspend fun delete(files: List<FileVersionTag>, hard: Boolean, refused: MutableSet<OdinClientErrorCode>): Set<Uuid> =
        files.chunked(DriveFileHttpProvider.FILE_BATCH).flatMapTo(mutableSetOf()) { chunk ->
            val answers = if (hard) {
                driveFileProvider.hardDeleteFiles(FilesyncProtocol.DriveAlias, chunk)
            } else {
                driveFileProvider.softDeleteFiles(FilesyncProtocol.DriveAlias, chunk)
            }
            val byCode = answers.results.groupBy({ it.errorCode }, { it.fileId })
            refused += byCode.keys.filterNotNull() - setOf(OdinClientErrorCode.VersionTagMismatch, OdinClientErrorCode.FileNotFound)
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
            if (e.errorCode !in ChangedElsewhere) throw e
            false
        }.also { if (!it) Logger.i(tag = TAG) { "node $nodeId changed elsewhere; left as it is" } }
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
            // Nothing lists those until a computer syncs, so saying where comes first.
            result.recovered > 0 -> FilesyncUiEvent.Notice(FilesyncNotice.RestoredToRecovered)
            result.skipped > 0 -> FilesyncUiEvent.Notice(FilesyncNotice.ChangedElsewhere)
            else -> FilesyncUiEvent.Notice(done)
        }
    )
    return result != null && result.skipped == 0
}
