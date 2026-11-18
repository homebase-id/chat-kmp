package id.homebase.core.ui.screens.filesync

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.DriveFileHttpProvider
import id.homebase.api.client.drives.files.FileVersionTag
import id.homebase.api.client.drives.files.PerFileBatchResult
import id.homebase.api.client.drives.upload.DriveUploadProvider
import id.homebase.api.client.drives.upload.FileUpdateInstructionSet
import id.homebase.api.client.drives.upload.UpdateFileByFileIdRequest
import id.homebase.api.client.drives.upload.UpdateLocale
import id.homebase.api.client.drives.upload.UpdateManifest
import id.homebase.api.client.drives.upload.UploadFileMetadata
import id.homebase.api.crypto.ByteArrayUtil
import id.homebase.core.filesync.FilesyncProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlin.uuid.Uuid

private const val TAG = "FilesyncEditor"

// What another device's newer word looks like in a per-file answer: the node is left as it is.
private val ChangedElsewhere = setOf(OdinClientErrorCode.VersionTagMismatch, OdinClientErrorCode.FileNotFound, OdinClientErrorCode.CannotUpdateNonActiveFile)

/**
 * [skipped]: items another device changed or deleted meanwhile, left as they are. [recovered]: restored where no listing
 * shows them until a computer moves them into Recovered files. [loopsIntoRecovered]: folders that were in each other came back there.
 */
class FilesyncWriteResult(val done: Int, val skipped: Int, val recovered: Int = 0, val tryAgainSoon: Boolean = false, val loopsIntoRecovered: Boolean = false)

/** Restores and empties Recently deleted exactly as filesync's deleted.rs does, so every desktop agrees with the phone. */
class FilesyncEditor(
    private val repository: FilesyncRepository,
    private val driveUploadProvider: DriveUploadProvider,
    private val driveFileProvider: DriveFileHttpProvider,
    private val uploader: FilesyncUploader,
) {
    /** Fires after any restore or empty, so a screen showing the drive can list it again. */
    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changed: SharedFlow<Unit> = _changed.asSharedFlow()

    // One write at a time: a restore picks names against the folder as it is, so two at once could pick the same one.
    private val writeLock = Mutex()
    private val notFound = FilesyncNotFoundTwice()
    private var lastRemove: TimeMark? = null

    /** Brings each of [nodeIds] back with the items its delete took along, and the removed folders above it first. */
    suspend fun restore(nodeIds: Set<Uuid>): FilesyncWriteResult = writeLock.withLock {
        // An Undo right after a delete: the queries below would miss it.
        lastRemove?.let { delay(QUERY_HOLD_BACK - it.elapsedNow()) }
        val trash = FilesyncTrash(repository.listDeleted())
        val tops = nodeIds.mapNotNull { id -> trash.all.nodes[id]?.takeIf { it.removed } }
        // One already live was restored by an earlier tap or another device: nothing to do, nothing to report.
        val gone = (nodeIds - tops.map { it.nodeId }.toSet()).let { it.size - repository.findNodes(it, FilesyncStatus.Live).nodes.size }
        val plans = tops.map(trash::restorePlan)
        val plan = plans.flatten().distinctBy { it.nodeId }
        val breaks = trash.loopBreaks(tops, plans)
        val breaking = breaks.atTop + breaks.toExit
        val exit = if (breaks.toExit.isEmpty()) null else loopExit(trash)
        val recoveredInTrash = exit?.folder == FilesyncProtocol.RecoveredFilesNodeId &&
            trash.all.nodes[FilesyncProtocol.RecoveredFilesNodeId]?.removed == true
        // The top instead once Recovered files' id proves taken.
        var exitFolder = exit?.folder
        var toMake = exit?.make == true
        fun into(node: FilesyncNode) = when (node.nodeId) {
            in breaks.atTop -> FilesyncProtocol.RootNodeId
            in breaks.toExit -> exitFolder!!
            else -> node.parentId
        }
        val chains = mutableMapOf<Uuid, List<FilesyncNode>>()
        fun chainAbove(node: FilesyncNode) = into(node).let { folder ->
            chains.getOrPut(folder) { trash.all.nodes[folder]?.let { trash.chainOf(it, stopAt = breaking) }.orEmpty() }
        }
        // A node waits for the planned folder it comes back into: a level after it.
        val planned = plan.associateBy { it.nodeId }
        val level = mutableMapOf<Uuid, Int>()
        fun levelOf(node: FilesyncNode): Int = level[node.nodeId] ?: run {
            level[node.nodeId] = 0
            (planned[into(node)]?.let { levelOf(it) + 1 } ?: 0).also { level[node.nodeId] = it }
        }
        plan.forEach(::levelOf)
        // Recovered files to be made counts as live: what comes back into it takes a free name there.
        val liveParents = repository.findNodes(plan.map(::into).filter { it !in level }, FilesyncStatus.Live).nodes.keys +
            listOfNotNull(FilesyncProtocol.RecoveredFilesNodeId.takeIf { toMake })

        val restored = mutableSetOf<Uuid>()
        // Folders of the plan deleted for good while it ran.
        val wentForGood = mutableSetOf<Uuid>()
        // In a Recovered files still in Recently deleted, it shows only once a computer brings that back.
        val intoRecovered = if (recoveredInTrash && FilesyncProtocol.RecoveredFilesNodeId !in level) breaks.toExit.toMutableSet() else mutableSetOf()
        val takenByFolder = mutableMapOf<Uuid, MutableSet<String>>()
        suspend fun taken(folder: Uuid) = takenByFolder.getOrPut(folder) { repository.takenNames(folder) }
        // Before the first wave, so nothing comes back at the top under the name of the Recovered files made later.
        if (toMake) taken(FilesyncProtocol.RootNodeId).add(FilesyncNames.key(FilesyncProtocol.RecoveredFilesName))
        var skipped = gone
        var sent = false
        var tryAgainSoon = false
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
                    val parent = into(node)
                    val orphan = when {
                        parent in restored -> false
                        parent in wentForGood -> true
                        parent in level -> null
                        // Recovered files too where it is in Recently deleted: the computers bring it back for what is in it.
                        node.nodeId in breaking -> false
                        parent == FilesyncProtocol.RootNodeId -> false
                        // A live folder that went with a delete is back once the removed folders it went with are, or come back in this restore.
                        parent in trash.itemOf -> chainAbove(node).let { chain ->
                            if (chain.any { it.nodeId in wentForGood }) intoRecovered += node.nodeId
                            false.takeIf { chain.all { !it.removed || it.nodeId in restored || it.nodeId in wentForGood || it.nodeId in level } }
                        }
                        parent in liveParents -> false
                        else -> true
                    }
                    if (orphan == true) intoRecovered += node.nodeId
                    val handle = trash.all.handles.getValue(node.nodeId)
                    val version = handle.fileVersionTag
                    when {
                        orphan == null || !handle.writable -> skipped++
                        node.nodeId in breaking -> rewrites += node
                        version != null && (orphan || taken(parent).add(node.nameKey)) -> batch += version to node
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
                    if (toMake && node.nodeId in breaks.toExit) {
                        toMake = false
                        sent = true
                        if (!uploader.createRecoveredFiles()) exitFolder = FilesyncProtocol.RootNodeId
                    }
                    val parent = into(node)
                    val taken = taken(parent)
                    val name = FilesyncNames.restoredName(node.name, taken)
                    if (name == null) {
                        skipped++
                        continue
                    }
                    sent = true
                    val handle = trash.all.handles.getValue(node.nodeId)
                    if (write(node.nodeId, handle, renamedContent(handle.content, name), parent)) {
                        restored += node.nodeId
                        taken += FilesyncNames.key(name)
                    } else {
                        skipped++
                        if (node.isFolder) maybeGone[handle.fileId] = node.nodeId
                    }
                }
                // A folder only said not found once stays, and what comes back into it waits for the next try.
                val notThere = repository.notThere(maybeGone.keys.toList())
                for ((fileId, folder) in maybeGone) {
                    when (notThere[fileId]) {
                        null -> notFound.found(fileId)
                        FilesyncNotThere.Tombstone -> wentForGood += folder
                        FilesyncNotThere.NoHeader -> if (notFound.confirms(fileId, trash.all.handles.getValue(folder).versionTag)) wentForGood += folder else tryAgainSoon = true
                    }
                }
            }
        } finally {
            // A failed request may still have restored some of its files.
            if (sent) _changed.tryEmit(Unit)
        }
        FilesyncWriteResult(
            restored.size, skipped, recovered = (intoRecovered intersect restored).size, tryAgainSoon = tryAgainSoon,
            loopsIntoRecovered = exitFolder == FilesyncProtocol.RecoveredFilesNodeId &&
                (!recoveredInTrash || FilesyncProtocol.RecoveredFilesNodeId in restored) && breaks.toExit.any { it in restored },
        )
    }

    /**
     * Where the folder that breaks a loop comes back, as filesync's loop_exit says: into Recovered files, made first where
     * the drive has none (one in Recently deleted the computers bring back); at the top where Recovered files is in a loop.
     */
    private suspend fun loopExit(trash: FilesyncTrash): FilesyncLoopExit {
        val recovered = FilesyncProtocol.RecoveredFilesNodeId
        val seen = mutableSetOf<Uuid>()
        var at = recovered
        while (at != FilesyncProtocol.RootNodeId) {
            if (!seen.add(at)) return FilesyncLoopExit(FilesyncProtocol.RootNodeId, make = false)
            val parent = repository.findNodes(listOf(at), FilesyncStatus.Live).nodes[at]?.parentId
                ?: trash.all.nodes[at]?.takeIf { it.removed }?.parentId
                ?: return FilesyncLoopExit(recovered, make = at == recovered)
            at = parent
        }
        return FilesyncLoopExit(recovered, make = false)
    }

    /** Moves [nodes] to Recently deleted, as [removeInOrder] does; one changed since stays. */
    suspend fun remove(nodes: List<FilesyncNode>, handles: Map<Uuid, FilesyncHandle>): FilesyncWriteResult = writeLock.withLock {
        var sent = false
        try {
            removeInOrder(nodes, handles, repository::listFolders) { files ->
                sent = true
                driveFileProvider.updateArchivalStatus(FilesyncProtocol.DriveAlias, ArchivalStatus.Removed, files)
            }
        } finally {
            // A failed request may still have removed some of its files.
            if (sent) {
                lastRemove = TimeSource.Monotonic.markNow()
                _changed.tryEmit(Unit)
            }
        }
    }

    /** Moves [nodes] into the last folder of [folderPath] as [planMove] plans it; one changed since stays where it is. */
    /** [moved] gets each node as it moves, so a move that fails partway still says which did. */
    suspend fun move(nodes: List<FilesyncNode>, handles: Map<Uuid, FilesyncHandle>, folderPath: List<Uuid>, moved: MutableList<Uuid> = mutableListOf()): FilesyncWriteResult =
        writeLock.withLock {
            val folderId = folderPath.last()
            val plan = planMove(nodes, handles, folderPath, repository.takenNames(folderId))
            var skipped = plan.skipped
            try {
                for ((node, name) in plan.moves) {
                    val handle = handles.getValue(node.nodeId)
                    if (write(node.nodeId, handle, renamedContent(handle.content, name), folderId)) moved += node.nodeId else skipped++
                }
            } finally {
                if (plan.moves.isNotEmpty()) _changed.tryEmit(Unit)
            }
            FilesyncWriteResult(moved.size, skipped)
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
            if (removed.nodes.values.none { it.removedMs?.let { at -> at < before } == true }) {
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
            byCode[null].orEmpty() + repository.notThere(byCode[OdinClientErrorCode.FileNotFound].orEmpty()).keys
        }

    /** False when the node changed or went since it was read; that's another device's newer word, not a failure. */
    internal suspend fun write(nodeId: Uuid, handle: FilesyncHandle, content: JsonObject, parentId: Uuid): Boolean {
        return driveUploadProvider.updateUnlessChangedElsewhere(liveNodeUpdate(nodeId, handle, content, parentId))
            .also { if (!it) Logger.i(tag = TAG) { "node $nodeId changed elsewhere; left as it is" } }
    }
}

/** False when the file changed or went since it was read; that's another device's newer word, not a failure. */
internal suspend fun DriveUploadProvider.updateUnlessChangedElsewhere(request: UpdateFileByFileIdRequest): Boolean =
    try {
        updateFileByFileId(request, onVersionConflict = { null }) != null
    } catch (e: NotFoundException) {
        if (!e.fromIdentity) throw e
        false
    } catch (e: ClientException) {
        if (e.errorCode !in ChangedElsewhere) throw e
        false
    }

/** A Filesync drive file's whole header replaced in place; [metadata], encrypted with [keyHeader], carries the versionTag guarding it. */
internal fun filesyncUpdate(fileId: Uuid, keyHeader: KeyHeader, metadata: UploadFileMetadata) = UpdateFileByFileIdRequest(
    driveId = FilesyncProtocol.DriveAlias,
    fileId = fileId,
    keyHeader = keyHeader,
    instructions = FileUpdateInstructionSet(
        transferIv = ByteArrayUtil.getRndByteArray(16),
        locale = UpdateLocale.Local,
        recipients = emptyList(),
        manifest = UpdateManifest.build(),
    ),
    metadata = metadata,
)

/** [content] (everything read, unknown keys too) as a live node in [parentId]: the node's own key with a new IV, guarded by its versionTag. */
internal suspend fun liveNodeUpdate(nodeId: Uuid, handle: FilesyncHandle, content: JsonObject, parentId: Uuid): UpdateFileByFileIdRequest {
    val keyHeader = handle.keyWithNewIv()
    return filesyncUpdate(handle.fileId, keyHeader, liveNodeMetadata(nodeId, handle, content, parentId, keyHeader))
}

internal fun FilesyncHandle.keyWithNewIv() = keyHeader.withNewIv()

/** The whole header of a node as it is read, with [content], under [keyHeader]: guarded by the version it was read at. */
internal suspend fun liveNodeMetadata(nodeId: Uuid, handle: FilesyncHandle, content: JsonObject, parentId: Uuid, keyHeader: KeyHeader): UploadFileMetadata =
    filesyncMetadata(nodeId, parentId, handle.fileType, content.toString(), ArchivalStatus.None, handle.versionTag).encryptContent(keyHeader)


// The legacy removal key goes with any write of the content (node schema, `rm`).
internal fun renamedContent(content: JsonObject, name: String) =
    JsonObject(content - REMOVAL_KEY + ("name" to JsonPrimitive(name)))

/**
 * One 404 never makes a file gone: the identity's second "not there" does, a minute to an hour after the first, at the
 * same version. Wall time, as Android's monotonic clock stops in deep sleep and would stretch the hour.
 */
internal class FilesyncNotFoundTwice(private val clock: Clock = Clock.System) {
    private data class Sighting(val version: Uuid?, val at: Instant)

    private val sightings = mutableMapOf<Uuid, Sighting>()

    /** Records that [fileId] at [version] wasn't found; true when that confirms an earlier time. */
    fun confirms(fileId: Uuid, version: Uuid?): Boolean {
        val now = clock.now()
        sightings.values.removeAll { now - it.at > Window.endInclusive }
        val since = sightings[fileId]?.takeIf { it.version == version }?.let { now - it.at }
        if (since != null && since in Window) return true.also { sightings.remove(fileId) }
        if (since == null) sightings[fileId] = Sighting(version, now)
        return false
    }

    /** A header found meanwhile clears the doubt. */
    fun found(fileId: Uuid) {
        sightings.remove(fileId)
    }

    private companion object {
        val Window = 1.minutes..1.hours
    }
}

/** Runs [write] and turns its outcome into the messages the user sees; true when everything was written. */
internal suspend fun reportWrite(
    done: FilesyncUiEvent,
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
    if (result == null) {
        send(FilesyncUiEvent.Error(failed))
        return false
    }
    // Nothing lists those until a computer syncs, so saying where comes first; a retry won't plan them, so it's said now.
    if (result.recovered > 0) send(FilesyncUiEvent.Notice(FilesyncNotice.RestoredToRecovered))
    if (result.tryAgainSoon) send(FilesyncUiEvent.Notice(FilesyncNotice.TryAgainSoon))
    if (result.loopsIntoRecovered) send(FilesyncUiEvent.Notice(FilesyncNotice.RestoredIntoRecovered))
    if (result.recovered == 0 && !result.tryAgainSoon && (result.skipped > 0 || !result.loopsIntoRecovered)) send(if (result.skipped > 0) FilesyncUiEvent.Notice(FilesyncNotice.ChangedElsewhere) else done)
    return result.skipped == 0
}

/**
 * Removes [nodes] (node schema, "Recently deleted"): each folder by itself before what is in it, then what is live
 * below it a level at a time, so whatever goes with a folder is removed no earlier than the folder. Each file is sent
 * at its version, up to a batch a request; one changed or gone since is left as it is, and what is in a folder that
 * stays is left too.
 */
internal suspend fun removeInOrder(
    nodes: List<FilesyncNode>,
    handles: Map<Uuid, FilesyncHandle>,
    list: suspend (folderIds: List<Uuid>) -> FilesyncNodes,
    mark: suspend (List<FileVersionTag>) -> PerFileBatchResult,
): FilesyncWriteResult {
    var removed = 0
    var skipped = 0
    val seen = mutableSetOf<Uuid>()
    var level = nodes.filter { it.removedMs == null }.map { it to handles[it.nodeId] }
    while (level.isNotEmpty()) {
        val sending = mutableListOf<Pair<FileVersionTag, FilesyncNode>>()
        for ((node, handle) in level) {
            if (!seen.add(node.nodeId)) continue
            // A node from a newer content version is never changed: nothing here could bring it back.
            val version = handle?.fileVersionTag?.takeIf { handle.writable }
            if (version == null) skipped++ else sending += version to node
        }
        val folders = mutableListOf<Uuid>()
        for (chunk in sending.chunked(DriveFileHttpProvider.FILE_BATCH)) {
            val byFile = chunk.associate { (file, node) -> file.fileId to node }
            val refused = mutableSetOf<OdinClientErrorCode>()
            for (answer in mark(chunk.map { it.first }).results) {
                val node = byFile.getValue(answer.fileId)
                when (val code = answer.errorCode) {
                    null -> {
                        removed++
                        if (node.isFolder) folders += node.nodeId
                    }
                    in ChangedElsewhere -> skipped++
                    else -> refused += code
                }
            }
            check(refused.isEmpty()) { "remove refused: $refused" }
        }
        if (folders.isEmpty()) break
        val inside = list(folders)
        level = inside.nodes.values.map { it to inside.handles[it.nodeId] }
    }
    return FilesyncWriteResult(removed, skipped)
}

internal class FilesyncMovePlan(val moves: List<Pair<FilesyncNode, String>>, val skipped: Int)

/**
 * What moving [nodes] into the last folder of [folderPath], the path from the top, writes: each live node not there
 * already, under its name or numbered "(1)", "(2)"… against [taken] and the names before it. Skipped: a folder going
 * into itself or below itself, a node from a newer content version, and the folders kept at the top (node schema).
 */
internal fun planMove(
    nodes: List<FilesyncNode>,
    handles: Map<Uuid, FilesyncHandle>,
    folderPath: List<Uuid>,
    taken: MutableSet<String>,
): FilesyncMovePlan {
    val folderId = folderPath.last()
    val moves = mutableListOf<Pair<FilesyncNode, String>>()
    var skipped = 0
    for (node in nodes) {
        if (node.parentId == folderId || node.removedMs != null) continue
        val handle = handles[node.nodeId]
        if (handle?.fileVersionTag == null || !handle.writable || !node.movable || node.nodeId in folderPath) {
            skipped++
            continue
        }
        val name = FilesyncNames.freeName(node.name, taken)
        taken += FilesyncNames.key(name)
        moves += node to name
    }
    return FilesyncMovePlan(moves, skipped)
}

private class FilesyncLoopExit(val folder: Uuid, val make: Boolean)
