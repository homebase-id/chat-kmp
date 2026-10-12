package id.homebase.core.ui.screens.filesync

import co.touchlab.kermit.Logger
import id.homebase.api.client.ClientException
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.NotFoundException
import id.homebase.api.client.OdinClientErrorCode
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.api.client.drives.files.DriveFileHttpProvider
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.uuid.Uuid

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

    /** Brings [nodeId] back with the items its delete took along, and the removed folders above it first. */
    suspend fun restore(nodeId: Uuid): FilesyncWriteResult {
        val removed = repository.listRemoved()
        val top = removed.nodes[nodeId] ?: return FilesyncWriteResult(0, 1)
        val plan = FilesyncTrash.restorePlan(top, removed.nodes)
        val planned = plan.mapTo(mutableSetOf()) { it.nodeId }
        val outsideParents = plan.map { it.parentId }.filter { it !in planned }
        val liveParents = repository.findNodes(outsideParents, ArchivalStatus.None).nodes.keys

        val restored = mutableSetOf<Uuid>()
        val takenByFolder = mutableMapOf<Uuid, MutableSet<String>>()
        var skipped = 0
        for (node in plan) {
            val target = when (node.parentId) {
                in planned -> node.parentId.takeIf { it in restored }
                FilesyncProtocol.RootNodeId, in liveParents -> node.parentId
                // Its folder was deleted for good: it comes back at the top level.
                else -> FilesyncProtocol.RootNodeId
            }
            if (target == null) {
                skipped++
                continue
            }
            val taken = takenByFolder.getOrPut(target) {
                repository.listFolder(target).nodes.mapTo(mutableSetOf()) { FilesyncNames.key(it.name) }
            }
            val name = FilesyncNames.restoredName(node.name, taken)
            val handle = removed.handles.getValue(node.nodeId)
            if (name == null || !write(node.nodeId, handle, restoredContent(handle.content, name), target)) {
                skipped++
                continue
            }
            restored += node.nodeId
            taken += FilesyncNames.key(name)
        }
        _changed.tryEmit(Unit)
        return FilesyncWriteResult(restored.size, skipped)
    }

    /**
     * Deletes [shown] for good, files before folders. Each is read again first and left alone if it changed since
     * the user saw it, so nothing removed or restored after they looked goes.
     */
    suspend fun deleteForGood(shown: FilesyncNodes): FilesyncWriteResult {
        val current = repository.findNodes(shown.nodes.keys, ArchivalStatus.Removed)
        val unchanged = shown.nodes.values.filter { node ->
            val now = current.handles[node.nodeId] ?: return@filter false
            now.versionTag == shown.handles[node.nodeId]?.versionTag
        }
        val (files, folders) = FilesyncTrash.emptyOrder(unchanged).partition { !it.isFolder }
        listOf(files, folders).forEach { batch ->
            batch.chunked(DELETE_BATCH).forEach { chunk ->
                driveFileProvider.deleteFiles(FilesyncProtocol.DriveAlias, chunk.map { current.handles.getValue(it.nodeId).fileId })
            }
        }
        _changed.tryEmit(Unit)
        return FilesyncWriteResult(unchanged.size, shown.nodes.size - unchanged.size)
    }

    /** False when the node changed or went since it was read; that's another device's newer word, not a failure. */
    internal suspend fun write(nodeId: Uuid, handle: FilesyncHandle, content: JsonObject, parentId: Uuid): Boolean {
        val request = liveNodeUpdate(nodeId, handle, content, parentId)
        return try {
            driveUploadProvider.updateFileByFileId(request, onVersionConflict = { null }) != null
        } catch (e: NotFoundException) {
            false
        } catch (e: ClientException) {
            if (e.errorCode != OdinClientErrorCode.FileNotFound) throw e
            false
        }.also { if (!it) Logger.i(TAG) { "node $nodeId changed elsewhere; left as it is" } }
    }

    private companion object {
        const val TAG = "FilesyncEditor"
        const val DELETE_BATCH = 100
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
        Logger.e(e, "FilesyncEditor") { "$done failed" }
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
