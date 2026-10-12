package id.homebase.core.ui.screens.filesync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.files.PayloadDamagedException
import id.homebase.chat.conversationlist.ExtendPermissionUiState
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import id.homebase.core.filesync.FilesyncPreferences
import id.homebase.core.filesync.FilesyncProtocol
import id.homebase.core.util.isWeb
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

private const val TAG = "FilesyncViewModel"

class FilesyncViewModel(
    private val repository: FilesyncRepository,
    private val uploadQueue: FilesyncUploadQueue,
    val extendPermissionViewModel: ExtendPermissionViewModel,
    private val filesyncPreferences: FilesyncPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesyncUiState(path = listOf(FilesyncCrumb(FilesyncProtocol.RootNodeId, null))))
    val uiState: StateFlow<FilesyncUiState> = _uiState.asStateFlow()

    val uploads: StateFlow<List<FilesyncUpload>> = uploadQueue.uploads

    // Buffered so a download that finishes while the screen is away is still handed off on return.
    private val _events = Channel<FilesyncUiEvent>(Channel.BUFFERED)
    val events: Flow<FilesyncUiEvent> = _events.receiveAsFlow()

    private var payloads: Map<Uuid, FilesyncPayload> = emptyMap()
    private var loadJob: Job? = null
    private var transferJob: Job? = null

    // Not cleared by the dialog closing: its confirm button dismisses it before the grant arrives.
    private var setupRequested = false

    init {
        viewModelScope.launch {
            combine(
                extendPermissionViewModel.permissionsChecked,
                extendPermissionViewModel.permissionsGranted,
            ) { checked, granted -> if (granted) true else if (checked) false else null }
                .collect { installed ->
                    val wasInstalled = _uiState.value.installed == true
                    _uiState.update { it.copy(installed = installed) }
                    if (installed == true && !wasInstalled) {
                        if (setupRequested) filesyncPreferences.setIconVisible(true)
                        setupRequested = false
                        _uiState.update { it.copy(setupInitiated = false) }
                        loadCurrentFolder()
                    }
                }
        }

        viewModelScope.launch {
            uploadQueue.changedFolders.collect { folderId ->
                if (_uiState.value.path.last().nodeId == folderId) loadCurrentFolder()
            }
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
                setupRequested = true
                _uiState.update { it.copy(setupInitiated = true) }
                extendPermissionViewModel.recheckPermissions()
            }

            FilesyncUiAction.DismissOnboardingClicked -> viewModelScope.launch { filesyncPreferences.setIconVisible(false) }

            FilesyncUiAction.RetryCheck -> extendPermissionViewModel.recheckPermissions()

            FilesyncUiAction.Refresh -> loadCurrentFolder()

            FilesyncUiAction.NavigateUp -> {
                val path = _uiState.value.path
                if (path.size > 1) openPath(path.dropLast(1))
            }

            is FilesyncUiAction.NavigateToCrumb -> openPath(_uiState.value.path.take(action.index + 1))

            is FilesyncUiAction.NodeClicked -> {
                val node = action.node
                if (node.isFolder) {
                    openPath(_uiState.value.path + FilesyncCrumb(node.nodeId, node.name))
                } else {
                    // The web can't hand a file to another app, so opening it means downloading it.
                    transfer(node, save = isWeb())
                }
            }

            is FilesyncUiAction.SaveClicked -> transfer(action.node, save = true)

            FilesyncUiAction.CancelTransfer -> transferJob?.cancel()

            is FilesyncUiAction.SaveHandedOff -> repository.discard(action.path)

            is FilesyncUiAction.FilesPicked -> uploadQueue.add(_uiState.value.path.last().nodeId, action.files)

            is FilesyncUiAction.RetryUpload -> uploadQueue.retry(action.id)

            is FilesyncUiAction.DismissUpload -> uploadQueue.dismiss(action.id)

            is FilesyncUiAction.CreateFolder -> uploadQueue.createFolder(_uiState.value.path.last().nodeId, action.name)
        }
    }

    private fun openPath(path: List<FilesyncCrumb>) {
        _uiState.update { it.copy(path = path, nodes = emptyList()) }
        loadCurrentFolder()
    }

    private fun loadCurrentFolder() {
        val folderId = _uiState.value.path.last().nodeId
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadFailed = false) }
            try {
                val folder = repository.listFolder(folderId)
                payloads = folder.payloads
                _uiState.update { it.copy(nodes = folder.nodes, isLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e, TAG) { "listing folder $folderId failed" }
                _uiState.update { it.copy(isLoading = false, loadFailed = true) }
            }
        }
    }

    private fun transfer(node: FilesyncNode, save: Boolean) {
        val payload = payloads[node.nodeId] ?: return
        if (transferJob?.isActive == true) return
        transferJob = viewModelScope.launch {
            _uiState.update { it.copy(transfer = FilesyncTransfer(node.nodeId)) }
            try {
                val path = repository.download(node.name, payload, forOpen = !save) { progress ->
                    _uiState.update { it.copy(transfer = it.transfer?.copy(progress = progress)) }
                }
                _events.send(
                    when {
                        path == null -> FilesyncUiEvent.Error(FilesyncError.DownloadFailed)
                        save -> FilesyncUiEvent.SaveFile(path, node.name)
                        else -> FilesyncUiEvent.OpenFile(path)
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: PayloadDamagedException) {
                Logger.e(e, TAG) { "file ${node.nodeId} failed its hash check" }
                _events.send(FilesyncUiEvent.Error(FilesyncError.Damaged))
            } catch (e: Exception) {
                Logger.e(e, TAG) { "download of ${node.nodeId} failed" }
                _events.send(FilesyncUiEvent.Error(FilesyncError.DownloadFailed))
            } finally {
                _uiState.update { it.copy(transfer = null) }
            }
        }
    }
}
