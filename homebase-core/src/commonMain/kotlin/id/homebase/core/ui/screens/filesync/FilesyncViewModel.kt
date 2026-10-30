package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.chat.conversationlist.ExtendPermissionUiState
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.core.filesync.FilesyncProtocol
import id.homebase.core.privatedrive.FilesyncPrivateDrive
import id.homebase.core.privatedrive.PrivateDriveOptIn
import id.homebase.core.ui.screens.webdrop.WebDropShareFlowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "FilesyncViewModel"
private const val ARCHIVE_FALLBACK_NAME = "Filesync"
private val FOLDER_REFRESH_DEBOUNCE = 500.milliseconds

@OptIn(FlowPreview::class)
class FilesyncViewModel(
    private val repository: FilesyncRepository,
    private val uploadQueue: FilesyncUploadQueue,
    private val editor: FilesyncEditor,
    val extendPermissionViewModel: ExtendPermissionViewModel,
    private val webDropShareFlowState: WebDropShareFlowState,
    private val privateDriveOptIn: PrivateDriveOptIn,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        FilesyncUiState(
            path = listOf(FilesyncCrumb(FilesyncProtocol.RootNodeId, null)),
            privateDrive = privateDriveOptIn.currentState(FilesyncPrivateDrive),
        )
    )
    val uiState: StateFlow<FilesyncUiState> = _uiState.asStateFlow()

    val uploads: StateFlow<List<FilesyncUpload>> = uploadQueue.uploads

    val search = TextFieldState()

    // Buffered so a download that finishes while the screen is away is still handed off on return.
    private val _events = Channel<FilesyncUiEvent>(Channel.BUFFERED)
    val events: Flow<FilesyncUiEvent> = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private val transfers = FilesyncTransfers(
        viewModelScope,
        repository,
        onTransfer = { change -> _uiState.update { it.copy(transfer = change(it.transfer)) } },
        send = _events::send,
        onWebDrop = { file ->
            // Seeded before navigating: WebDropViewModel takes the draft when it's created.
            webDropShareFlowState.setDraft(listOf(file))
            _events.send(FilesyncUiEvent.OpenWebDrop)
        },
    )

    private var setupPaused = false

    init {
        addCloseable(extendPermissionViewModel)
        viewModelScope.launch {
            snapshotFlow { search.text.toString() }.collect { text -> arrange { it.copy(query = text) } }
        }

        viewModelScope.launch {
            combine(
                extendPermissionViewModel.permissionsChecked,
                extendPermissionViewModel.permissionsGranted,
            ) { checked, granted -> if (granted) true else if (checked) false else null }
                .collect { installed ->
                    val wasInstalled = _uiState.value.installed == true
                    _uiState.update { it.copy(installed = installed) }
                    if (installed == true && !wasInstalled) {
                        _uiState.update { it.copy(setupInitiated = false) }
                        loadCurrentFolder()
                        launch { editor.purgeOnce() }
                    }
                }
        }

        viewModelScope.launch {
            // A batch of small files lands faster than a folder lists.
            uploadQueue.changedFolders
                .filter { it == _uiState.value.path.last().nodeId }
                .debounce(FOLDER_REFRESH_DEBOUNCE)
                .collect { loadCurrentFolder() }
        }

        viewModelScope.launch {
            editor.changed.collect { loadCurrentFolder() }
        }

        viewModelScope.launch {
            uploadQueue.errors.collect { _events.send(FilesyncUiEvent.Error(it)) }
        }

        viewModelScope.launch {
            privateDriveOptIn.state(FilesyncPrivateDrive).collect { privateDrive ->
                val old = _uiState.getAndUpdate { it.copy(privateDrive = privateDrive) }
                // The server hid the drive's files from this device until it held the key.
                if (old.privateDrive.needsSignIn && !privateDrive.needsSignIn && old.installed == true) loadCurrentFolder()
            }
        }

        viewModelScope.launch {
            extendPermissionViewModel.checkFailed.collect { failed -> _uiState.update { it.copy(checkFailed = failed) } }
        }

        viewModelScope.launch {
            // Cancelled in the owner console while away: coming back must not ask again.
            extendPermissionViewModel.navigateAwayRequest.collect { setupPaused = false }
        }

        viewModelScope.launch {
            extendPermissionViewModel.uiState
                .filter { it is ExtendPermissionUiState.Dismissed }
                .collect { _uiState.update { s -> s.copy(setupInitiated = false) } }
        }
    }

    fun onAction(action: FilesyncUiAction) {
        when (action) {
            FilesyncUiAction.SetupClicked -> {
                _uiState.update { it.copy(setupInitiated = true) }
                extendPermissionViewModel.recheckPermissions()
            }

            // Leaving Chat makes setup's verdict stale (installing an installed app re-registers it): recheck on return.
            FilesyncUiAction.Paused -> if (_uiState.value.setupInitiated) {
                setupPaused = true
                extendPermissionViewModel.dismissDialog()
            }

            FilesyncUiAction.Resumed -> if (setupPaused) {
                setupPaused = false
                onAction(FilesyncUiAction.SetupClicked)
            }

            FilesyncUiAction.SetupLeft -> setupPaused = false

            FilesyncUiAction.RetryCheck -> extendPermissionViewModel.recheckPermissions()

            FilesyncUiAction.Refresh -> loadCurrentFolder()

            FilesyncUiAction.NavigateUp -> {
                val path = _uiState.value.path
                if (path.size > 1) openPath(path.dropLast(1))
            }

            is FilesyncUiAction.NavigateToCrumb -> openPath(_uiState.value.path.take(action.index + 1))

            is FilesyncUiAction.NodeClicked -> open(action.node)

            is FilesyncUiAction.SortBy -> arrange { it.copy(sort = it.sort.by(action.key)) }

            is FilesyncUiAction.FilterTag -> arrange { it.copy(tagFilter = action.name.takeIf { name -> name != it.tagFilter }) }

            is FilesyncUiAction.Select -> _uiState.update { it.copy(selection = it.selection.select(action.nodeId, action.mode, it.visibleIds)) }

            FilesyncUiAction.SelectAll -> _uiState.update { it.copy(selection = it.selection.all(it.visibleIds)) }

            FilesyncUiAction.ClearSelection -> _uiState.update { it.copy(selection = FilesyncSelection()) }

            is FilesyncUiAction.MoveSelection -> _uiState.update { it.copy(selection = it.selection.move(action.by, action.extend, it.visibleIds)) }

            is FilesyncUiAction.SaveClicked -> transfers.save(action.node)

            is FilesyncUiAction.ShareClicked -> transfers.share(action.node)

            is FilesyncUiAction.WebDropClicked -> transfers.webDrop(action.node)

            is FilesyncUiAction.SendToChatClicked -> transfers.sendToChat(action.node)

            FilesyncUiAction.CancelTransfer -> transfers.cancel()

            is FilesyncUiAction.Bulk -> when (action.bulk) {
                FilesyncBulkAction.Download -> zipSelected(transfers::saveZip)
                FilesyncBulkAction.Share -> zipSelected(transfers::shareZip)
                FilesyncBulkAction.Chat -> zipSelected(transfers::sendZipToChat)
                FilesyncBulkAction.WebDrop -> zipSelected(transfers::webDropZip)
                FilesyncBulkAction.Delete -> onAction(FilesyncUiAction.AskDelete(_uiState.value.selected.filter { it.removedMs == null }))
                FilesyncBulkAction.Restore -> onAction(FilesyncUiAction.Restore(_uiState.value.selectedRemovedIds))
            }

            is FilesyncUiAction.SaveHandedOff -> repository.discard(action.path)

            is FilesyncUiAction.FilesPicked -> uploadQueue.add(_uiState.value.path.last().nodeId, action.files)

            is FilesyncUiAction.RetryUpload -> uploadQueue.retry(action.id)

            is FilesyncUiAction.DismissUpload -> uploadQueue.dismiss(action.id)

            is FilesyncUiAction.CreateFolder -> uploadQueue.createFolder(_uiState.value.path.last().nodeId, action.name)

            FilesyncUiAction.ToggleShowDeleted -> {
                val show = !_uiState.value.showDeleted
                // A removed folder can only be browsed while deleted files show.
                val path = if (show) _uiState.value.path else _uiState.value.path.takeWhile { it.removedMs == null }
                arrange { it.copy(showDeleted = show, path = path).withNodes(if (path == it.path) it.nodes else emptyList()) }
                loadCurrentFolder()
            }

            is FilesyncUiAction.Restore -> viewModelScope.launch {
                reportWrite(FilesyncNotice.Restored, FilesyncError.RestoreFailed, _events::send) { editor.restore(action.nodeIds) }
            }

            is FilesyncUiAction.AskDelete -> if (action.nodes.isNotEmpty()) _uiState.update { it.copy(confirmDelete = action.nodes) }

            FilesyncUiAction.DismissDelete -> _uiState.update { it.copy(confirmDelete = null) }

            FilesyncUiAction.ConfirmDelete -> {
                val nodes = _uiState.getAndUpdate { it.copy(confirmDelete = null) }.confirmDelete ?: return
                val handles = transfers.handles
                viewModelScope.launch {
                    val result = try {
                        editor.remove(nodes, handles)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e(e, TAG) { "removing ${nodes.size} nodes failed" }
                        _events.send(FilesyncUiEvent.Error(FilesyncError.DeleteFailed))
                        return@launch
                    }
                    _events.send(
                        if (result.skipped > 0) FilesyncUiEvent.Notice(FilesyncNotice.ChangedElsewhere)
                        else FilesyncUiEvent.Removed(nodes.mapTo(mutableSetOf()) { it.nodeId })
                    )
                }
            }

            FilesyncUiAction.MakePrivate -> {
                val url = privateDriveOptIn.makePrivateUrl(FilesyncPrivateDrive, extendPermissionViewModel.callbackUrl())
                if (url != null) _events.trySend(FilesyncUiEvent.OpenOwnerConsole(url))
            }

            FilesyncUiAction.SignInAgain -> viewModelScope.launch {
                privateDriveOptIn.signInAgain(
                    open = { _events.send(FilesyncUiEvent.OpenSignIn(it)) },
                    failed = { _events.send(FilesyncUiEvent.Error(FilesyncError.SignInAgainFailed)) },
                )
            }
        }
    }

    fun onSignInCallback(url: String) {
        viewModelScope.launch { privateDriveOptIn.handleSignInCallback(url) }
    }

    private fun zipSelected(start: (List<FilesyncNode>, String) -> Unit) {
        val state = _uiState.value
        val nodes = state.selected.filter { it.removedMs == null }
        if (nodes.isEmpty()) return
        start(nodes, FilesyncArchive.name(nodes, state.path.last().name, ARCHIVE_FALLBACK_NAME))
    }

    private fun open(node: FilesyncNode) {
        when {
            node.isFolder -> openPath(_uiState.value.path + FilesyncCrumb(node.nodeId, node.name, node.removedMs))
            // A deleted file is restored, not opened; the screen asks first.
            node.removedMs == null -> transfers.open(node)
        }
    }

    private fun openPath(path: List<FilesyncCrumb>) {
        search.clearText()
        arrange { it.copy(path = path, query = "", tagFilter = null, selection = FilesyncSelection()).withNodes(emptyList()) }
        loadCurrentFolder()
    }

    private fun arrange(change: (FilesyncUiState) -> FilesyncUiState) = _uiState.update { change(it).arrange() }

    private fun loadCurrentFolder() {
        val path = _uiState.value.path
        val folderId = path.last().nodeId
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadFailed = false) }
            try {
                val removed = path.filter { it.removedMs != null }.map { it.nodeId }
                val showDeleted = _uiState.value.showDeleted
                val (folder, now) = coroutineScope {
                    val folder = async { repository.listFolder(folderId, includeRemoved = showDeleted) }
                    // A restore from anywhere makes a removed crumb live again: ask the drive, not the trail.
                    val now = async { if (removed.isEmpty()) emptyMap() else repository.findNodes(removed, FilesyncStatus.Removed, FilesyncStatus.Live).nodes }
                    folder.await() to now.await()
                }
                // Recovered files stays hidden until a computer moves something into it; nothing removes it when empty.
                val recovered = FilesyncProtocol.RecoveredFilesNodeId
                val listed = if (folderId == FilesyncProtocol.RootNodeId && recovered in folder.nodes && !repository.holdsAny(recovered, showDeleted)) {
                    folder.nodes - recovered
                } else {
                    folder.nodes
                }
                transfers.handles = folder.handles
                // One deleted for good stays removed: nothing may be uploaded into it.
                val current = path.map { crumb ->
                    val node = now[crumb.nodeId]
                    if (crumb.removedMs == null || node == null) crumb else crumb.copy(removedMs = node.removedMs)
                }
                // In a removed folder, what is still live but written before its removal was deleted with it.
                val folderDeletedMs = current.last().removedMs
                val nodes = listed.values.filter { folderDeletedMs == null || it.removed || !FilesyncTrash.wentWith(it, folderDeletedMs) }
                arrange { state -> state.copy(isLoading = false, path = if (state.path == path) current else state.path).withNodes(nodes) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e, TAG) { "listing folder $folderId failed" }
                _uiState.update { it.copy(isLoading = false, loadFailed = true) }
            }
        }
    }
}
