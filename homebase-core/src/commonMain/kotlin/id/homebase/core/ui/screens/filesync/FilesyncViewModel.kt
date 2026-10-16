package id.homebase.core.ui.screens.filesync

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.ArchivalStatus
import id.homebase.chat.conversationlist.ExtendPermissionUiState
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.core.filesync.FilesyncProtocol
import id.homebase.core.ui.screens.webdrop.WebDropShareFlowState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "FilesyncViewModel"
private val FOLDER_REFRESH_DEBOUNCE = 500.milliseconds

@OptIn(FlowPreview::class)
class FilesyncViewModel(
    private val repository: FilesyncRepository,
    private val uploadQueue: FilesyncUploadQueue,
    private val editor: FilesyncEditor,
    val extendPermissionViewModel: ExtendPermissionViewModel,
    private val webDropShareFlowState: WebDropShareFlowState,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesyncUiState(path = listOf(FilesyncCrumb(FilesyncProtocol.RootNodeId, null))))
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

    init {
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
            extendPermissionViewModel.checkFailed.collect { failed -> _uiState.update { it.copy(checkFailed = failed) } }
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

            is FilesyncUiAction.Select -> _uiState.update { it.copy(selectedId = action.nodeId) }

            is FilesyncUiAction.MoveSelection -> _uiState.update { state ->
                val index = state.visible.indexOfFirst { it.nodeId == state.selectedId }
                val next = if (index < 0) 0 else (index + action.by).coerceIn(0, state.visible.lastIndex)
                state.copy(selectedId = state.visible.getOrNull(next)?.nodeId)
            }

            is FilesyncUiAction.SaveClicked -> transfers.save(action.node)

            is FilesyncUiAction.ShareClicked -> transfers.share(action.node)

            is FilesyncUiAction.WebDropClicked -> transfers.webDrop(action.node)

            FilesyncUiAction.CancelTransfer -> transfers.cancel()

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
                reportWrite(FilesyncNotice.Restored, FilesyncError.RestoreFailed, _events::send) { editor.restore(setOf(action.nodeId)) }
            }
        }
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
        arrange { it.copy(path = path, query = "", tagFilter = null, selectedId = null).withNodes(emptyList()) }
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
                val folder = repository.listFolder(folderId, includeRemoved = _uiState.value.showDeleted)
                transfers.handles = folder.handles
                // A restore from anywhere makes a removed crumb live again: ask the drive, not the trail.
                val removed = path.filter { it.removedMs != null }.map { it.nodeId }
                val stillRemoved = if (removed.isEmpty()) emptyMap() else repository.findNodes(removed, ArchivalStatus.Removed).nodes
                val current = path.map { crumb -> crumb.copy(removedMs = crumb.removedMs?.let { stillRemoved[crumb.nodeId]?.removedMs }) }
                // In a removed folder, what is still live but written before its removal was deleted with it.
                val folderDeletedMs = current.last().removedMs
                val nodes = folder.nodes.values.filter { folderDeletedMs == null || it.removed || !FilesyncTrash.wentWith(it, folderDeletedMs) }
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
