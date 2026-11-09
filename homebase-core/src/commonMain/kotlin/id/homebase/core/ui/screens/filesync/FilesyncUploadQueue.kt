@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package id.homebase.core.ui.screens.filesync

import co.touchlab.kermit.Logger
import id.homebase.api.HomebaseProtocol
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.eventbus.BackendEvent
import id.homebase.api.client.eventbus.EventBus
import id.homebase.api.client.isStorageFull
import id.homebase.api.coroutines.ioDispatcher
import id.homebase.api.coroutines.supervisedScope
import id.homebase.api.file.FileOperationsProvider
import id.homebase.core.files.materializeForUpload
import id.homebase.core.files.modifiedAtMs
import id.homebase.core.files.toUploadPath
import id.homebase.core.util.isDesktop
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.size
import kotlin.concurrent.Volatile
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Outside any screen so leaving the Filesync tab doesn't cancel uploads; files go up one at a time. */
class FilesyncUploadQueue(
    private val repository: FilesyncRepository,
    private val uploader: FilesyncUploader,
    private val fileOps: FileOperationsProvider,
    eventBus: EventBus,
    private val inheritShares: suspend (parentId: Uuid, nodeId: Uuid, keyHeader: KeyHeader) -> Boolean,
    private val memberWrites: FilesyncMemberWrites,
    private val peerSends: FilesyncPeerSends,
) {
    private val scope = supervisedScope("filesync-upload-queue")

    // Everything one identity started; cancelled and joined at sign-out before its state is cleared.
    @Volatile
    private var work = SupervisorJob(scope.coroutineContext.job)

    private val _uploads = MutableStateFlow<List<FilesyncUpload>>(emptyList())
    val uploads: StateFlow<List<FilesyncUpload>> = _uploads.asStateFlow()

    /** A folder whose contents just changed, so a screen showing it can refresh. */
    private val _changedFolders = MutableSharedFlow<Uuid>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changedFolders: SharedFlow<Uuid> = _changedFolders.asSharedFlow()

    // Lossy on purpose: the failed row stays; a burst of failures must never block the queue behind a snackbar.
    private val _errors = MutableSharedFlow<FilesyncError>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val errors: SharedFlow<FilesyncError> = _errors.asSharedFlow()

    private var pending = Channel<Uuid>(Channel.UNLIMITED)

    private val peerNamesSent = mutableMapOf<Uuid, MutableSet<String>>()

    // Kept out of the uploads shown: a target holds file keys.
    private val peerTargets = MutableStateFlow<Map<Uuid, FilesyncPeerTarget>>(emptyMap())

    // A batch lists each target folder once; names given out are added so later files in the batch avoid them.
    private val namingLock = Mutex()
    private val batchTakenKeys = mutableMapOf<Uuid, MutableSet<String>>()

    init {
        scope.launch {
            eventBus.events.collect { if (it is BackendEvent.SessionEnded) reset() }
        }
        startWorker()
    }

    /** Each file is queued as soon as it's ready, so the first upload doesn't wait for the whole pick to be copied; the result is how many were queued, once every file has been copied. */
    fun add(parentId: Uuid, files: List<PlatformFile>, peer: FilesyncPeerTarget? = null): Deferred<Int> =
        scope.async(work) {
            var queued = 0
            for (file in files) {
                val picked = try {
                    withContext(ioDispatcher) { prepare(file) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(e, tag = TAG) { "a picked file couldn't be copied for upload" }
                    _errors.tryEmit(FilesyncError.UploadFailed)
                    continue
                }
                if (picked == null) {
                    _errors.tryEmit(FilesyncError.TooLarge)
                    continue
                }
                val upload = FilesyncUpload(Uuid.random(), parentId, picked, owner = peer?.owner)
                enqueue(upload, peer)
                queued++
            }
            queued
        }

    // Copied into the sandbox while the picker's grant is live: Android and iOS revoke it later.
    private suspend fun prepare(file: PlatformFile): FilesyncPickedFile? {
        if (file.size() > HomebaseProtocol.MaxPlaintextPayloadBytes) return null
        // A desktop pick is a real path with no grant to lose, so it's read in place rather than copied first.
        val desktop = isDesktop()
        val modifiedMs = file.modifiedAtMs()
        val snapshot = if (desktop) file else file.materializeForUpload(fileOps)
        return FilesyncPickedFile(snapshot.toUploadPath(fileOps), file.name, modifiedMs, ownedCopy = !desktop)
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
        scope.launch(work) {
            reporting(FilesyncError.FolderFailed, "creating a folder in $parentId") {
                // Listed fresh: a folder isn't part of an upload batch.
                val name = namingLock.withLock {
                    val taken = repository.takenNames(parentId)
                    FilesyncNames.freeName(requestedName, taken).also { batchTakenKeys[parentId]?.add(FilesyncNames.key(it)) }
                }
                val (nodeId, keyHeader) = uploader.createFolder(parentId, name)
                _changedFolders.emit(parentId)
                inherit(parentId, nodeId, keyHeader)
            }
        }
    }

    /** Drops every upload at sign-out: they belong to the identity that picked them. */
    private suspend fun reset() {
        work.cancelAndJoin()
        work = SupervisorJob(scope.coroutineContext.job)
        _uploads.value.forEach { if (it.file.ownedCopy) repository.discard(it.file.path) }
        _uploads.value = emptyList()
        peerTargets.value = emptyMap()
        batchTakenKeys.clear()
        namingLock.withLock { peerNamesSent.clear() }
        pending = Channel(Channel.UNLIMITED)
        startWorker()
    }

    private fun startWorker() {
        val queue = pending
        scope.launch(work) {
            for (id in queue) {
                val upload = _uploads.value.firstOrNull { it.id == id } ?: continue
                reporting(FilesyncError.UploadFailed, "upload $id", onFailure = { updateUpload(id) { it.copy(failed = true, progress = null) } }) {
                    upload(upload)
                }
                // An idle queue lists afresh next time: the desktop may have added files meanwhile.
                if (queue.isEmpty) namingLock.withLock { batchTakenKeys.clear() }
            }
        }
    }

    private suspend fun upload(upload: FilesyncUpload) {
        peerTargets.value[upload.id]?.let { return sendToPeer(upload, it) }
        val name = upload.name ?: namingLock.withLock {
            val taken = batchTakenKeys.getOrPut(upload.parentId) {
                // A failed upload's name never reached the server, but its retry will still use it.
                repository.takenNames(upload.parentId).apply {
                    _uploads.value.forEach { if (it.parentId == upload.parentId) it.name?.let { name -> add(FilesyncNames.key(name)) } }
                }
            }
            FilesyncNames.freeName(upload.file.name, taken).also { taken += FilesyncNames.key(it) }
        }.also { chosen -> updateUpload(upload.id) { it.copy(name = chosen) } }

        val keyHeader = uploader.uploadFile(upload.id, upload.parentId, upload.file.path, name, upload.file.modifiedMs) { fraction ->
            updateUpload(upload.id) { it.copy(progress = fraction) }
        }
        finish(upload)
        _changedFolders.emit(upload.parentId)
        inherit(upload.parentId, upload.id, keyHeader)
    }

    /** Sent through this identity's server, which tells how it went later ([FilesyncPeerSends]); the row goes once it's queued there. */
    private suspend fun sendToPeer(upload: FilesyncUpload, target: FilesyncPeerTarget) {
        val name = upload.name ?: when (target) {
            is FilesyncPeerTarget.Add -> namingLock.withLock {
                // Kept for the session: what reached the owner's folder shows there only once it's listed again.
                val sent = peerNamesSent.getOrPut(target.folderId) { mutableSetOf() }
                val queued = _uploads.value.filter { it.owner == target.owner && it.parentId == target.folderId }.mapNotNull { it.name?.let(FilesyncNames::key) }
                FilesyncNames.freeName(upload.file.name, target.takenKeys + sent + queued).also { sent += FilesyncNames.key(it) }
            }
            is FilesyncPeerTarget.Replace -> target.node.name
        }.also { chosen -> updateUpload(upload.id) { it.copy(name = chosen) } }
        val sent = memberWrites.send(target, upload.file.path, name, upload.file.modifiedMs) { fraction ->
            updateUpload(upload.id) { it.copy(progress = fraction) }
        }
        // A file its owner's device hasn't taken in yet refuses a kept key: the edit goes again sealed, from the same copy.
        val reseal = (target as? FilesyncPeerTarget.Replace)?.takeIf { !it.sealed }?.copy(sealed = true)
        if (reseal == null) {
            finish(upload)
            peerSends.track(sent)
        } else {
            remove(upload.id)
            peerSends.track(
                sent,
                onKeyHeaderRefused = { enqueue(upload.copy(name = name, progress = null), reseal) },
                onSettled = { discardCopy(upload) },
            )
        }
    }

    private fun enqueue(upload: FilesyncUpload, peer: FilesyncPeerTarget?) {
        if (peer != null) peerTargets.update { it + (upload.id to peer) }
        _uploads.update { it + upload }
        pending.trySend(upload.id)
    }

    // Uploaded either way: a share that didn't follow is told, not taken for a failed upload.
    private suspend fun inherit(parentId: Uuid, nodeId: Uuid, keyHeader: KeyHeader) {
        var shared = false
        reporting(FilesyncError.InheritFailed, "sharing $nodeId like its folder") { shared = inheritShares(parentId, nodeId, keyHeader) }
        // Listed again, so the new node shows as shared.
        if (shared) _changedFolders.emit(parentId)
    }

    private suspend fun reporting(error: FilesyncError, what: String, onFailure: () -> Unit = {}, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.e(e, TAG) { "$what failed" }
            onFailure()
            _errors.tryEmit(if (e.isStorageFull()) FilesyncError.StorageFull else error)
        }
    }

    private fun finish(upload: FilesyncUpload) {
        discardCopy(upload)
        remove(upload.id)
    }

    private fun discardCopy(upload: FilesyncUpload) {
        if (upload.file.ownedCopy) repository.discard(upload.file.path)
    }

    private fun remove(id: Uuid) {
        peerTargets.update { it - id }
        _uploads.update { list -> list.filterNot { it.id == id } }
    }

    private fun updateUpload(id: Uuid, change: (FilesyncUpload) -> FilesyncUpload) {
        _uploads.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private companion object {
        const val TAG = "FilesyncUploadQueue"
    }
}
