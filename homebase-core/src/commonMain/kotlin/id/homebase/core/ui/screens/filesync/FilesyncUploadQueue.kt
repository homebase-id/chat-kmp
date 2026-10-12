package id.homebase.core.ui.screens.filesync

import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.WholePercentProgressGate
import id.homebase.api.coroutines.supervisedScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/** Outside any screen so leaving the Filesync tab doesn't cancel uploads; files go up one at a time. */
class FilesyncUploadQueue(
    private val repository: FilesyncRepository,
    private val uploader: FilesyncUploader,
) {
    private val scope = supervisedScope("filesync-upload-queue")

    private val _uploads = MutableStateFlow<List<FilesyncUpload>>(emptyList())
    val uploads: StateFlow<List<FilesyncUpload>> = _uploads.asStateFlow()

    /** A folder whose contents just changed, so a screen showing it can refresh. */
    private val _changedFolders = MutableSharedFlow<Uuid>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changedFolders: SharedFlow<Uuid> = _changedFolders.asSharedFlow()

    // Lossy on purpose: the failed row stays; a burst of failures must never block the queue behind a snackbar.
    private val _errors = MutableSharedFlow<FilesyncError>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val errors: SharedFlow<FilesyncError> = _errors.asSharedFlow()

    private var pending = Channel<Uuid>(Channel.UNLIMITED)

    // A batch lists each target folder once; names given out are added so later files in the batch avoid them.
    private val namingLock = Mutex()
    private val batchTakenKeys = mutableMapOf<Uuid, MutableSet<String>>()

    init {
        startWorker()
    }

    fun enqueue(parentId: Uuid, files: List<FilesyncPickedFile>) {
        val uploads = files.map { FilesyncUpload(Uuid.random(), parentId, it) }
        _uploads.update { it + uploads }
        uploads.forEach { pending.trySend(it.id) }
    }

    fun retry(id: Uuid) {
        if (_uploads.value.none { it.id == id && it.failed }) return
        updateUpload(id) { it.copy(failed = false) }
        pending.trySend(id)
    }

    fun dismiss(id: Uuid) {
        _uploads.value.firstOrNull { it.id == id && it.failed }?.let(::finish)
    }

    fun createFolder(parentId: Uuid, requestedName: String) {
        scope.launch {
            reporting(FilesyncError.FolderFailed, "creating a folder in $parentId") {
                // Listed fresh: a folder isn't part of an upload batch.
                val name = namingLock.withLock {
                    val taken = listedKeys(parentId)
                    FilesyncNames.freeName(requestedName, taken).also { batchTakenKeys[parentId]?.add(FilesyncNames.key(it)) }
                }
                uploader.createFolder(parentId, name)
                _changedFolders.emit(parentId)
            }
        }
    }

    /** Drops every upload at sign-out: they belong to the identity that picked them. */
    fun reset() {
        scope.coroutineContext.cancelChildren()
        _uploads.value.forEach { if (it.file.ownedCopy) repository.discard(it.file.path) }
        _uploads.value = emptyList()
        batchTakenKeys.clear()
        pending = Channel(Channel.UNLIMITED)
        startWorker()
    }

    private fun startWorker() {
        val queue = pending
        scope.launch {
            for (id in queue) {
                val upload = _uploads.value.firstOrNull { it.id == id } ?: continue
                reporting(FilesyncError.UploadFailed, "upload $id") {
                    try {
                        upload(upload)
                    } catch (e: Exception) {
                        if (e !is CancellationException) updateUpload(id) { it.copy(failed = true, progress = null) }
                        throw e
                    }
                }
                // An idle queue lists afresh next time: the desktop may have added files meanwhile.
                if (queue.isEmpty) namingLock.withLock { batchTakenKeys.clear() }
            }
        }
    }

    private suspend fun upload(upload: FilesyncUpload) {
        val name = upload.name ?: namingLock.withLock {
            val taken = batchTakenKeys.getOrPut(upload.parentId) { listedKeys(upload.parentId) }
            FilesyncNames.freeName(upload.file.name, taken).also { taken += FilesyncNames.key(it) }
        }.also { chosen -> updateUpload(upload.id) { it.copy(name = chosen) } }

        val gate = WholePercentProgressGate()
        uploader.uploadFile(upload.parentId, upload.file.path, name, upload.file.modifiedMs) { fraction ->
            if (gate.admit(fraction * 100) != null) updateUpload(upload.id) { it.copy(progress = fraction) }
        }
        finish(upload)
        // One refresh per batch for a folder, not one per file.
        if (_uploads.value.none { it.parentId == upload.parentId && !it.failed }) _changedFolders.emit(upload.parentId)
    }

    private suspend fun listedKeys(folderId: Uuid): MutableSet<String> =
        repository.listFolder(folderId).nodes.mapTo(mutableSetOf()) { FilesyncNames.key(it.name) }

    private suspend fun reporting(error: FilesyncError, what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e, TAG) { "$what failed" }
            _errors.tryEmit(error)
        }
    }

    private fun finish(upload: FilesyncUpload) {
        if (upload.file.ownedCopy) repository.discard(upload.file.path)
        _uploads.update { list -> list.filterNot { it.id == upload.id } }
    }

    private fun updateUpload(id: Uuid, change: (FilesyncUpload) -> FilesyncUpload) {
        _uploads.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private companion object {
        const val TAG = "FilesyncUploadQueue"
    }
}
