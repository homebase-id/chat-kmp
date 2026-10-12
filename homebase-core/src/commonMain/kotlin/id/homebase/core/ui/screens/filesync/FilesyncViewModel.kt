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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

private const val TAG = "FilesyncViewModel"

class FilesyncViewModel(
    private val repository: FilesyncRepository,
    private val filesyncPermissionViewModel: ExtendPermissionViewModel,
    private val filesyncPreferences: FilesyncPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesyncUiState(path = listOf(FilesyncCrumb(FilesyncProtocol.RootNodeId, null))))
    val uiState: StateFlow<FilesyncUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<FilesyncUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<FilesyncUiEvent> = _events.asSharedFlow()

    val extendPermissionViewModel: ExtendPermissionViewModel
        get() = filesyncPermissionViewModel

    private var payloads: Map<Uuid, FilesyncPayload> = emptyMap()
    private var loadJob: Job? = null
    private var transferJob: Job? = null

    init {
        viewModelScope.launch {
            combine(
                filesyncPermissionViewModel.permissionsChecked,
                filesyncPermissionViewModel.permissionsGranted,
            ) { checked, granted -> if (granted) true else if (checked) false else null }
                .collect { installed ->
                    val wasInstalled = _uiState.value.installed == true
                    _uiState.update { it.copy(installed = installed) }
                    if (installed == true && !wasInstalled) {
                        if (_uiState.value.setupInitiated) filesyncPreferences.setIconVisible(true)
                        _uiState.update { it.copy(setupInitiated = false) }
                        loadCurrentFolder()
                    }
                }
        }

        viewModelScope.launch {
            filesyncPermissionViewModel.uiState
                .filter { it is ExtendPermissionUiState.Dismissed }
                .collect { _uiState.update { s -> s.copy(setupInitiated = false) } }
        }
    }

    fun onAction(action: FilesyncUiAction) {
        when (action) {
            FilesyncUiAction.SetupClicked -> {
                _uiState.update { it.copy(setupInitiated = true) }
                filesyncPermissionViewModel.recheckPermissions()
            }

            FilesyncUiAction.DismissOnboardingClicked -> viewModelScope.launch {
                filesyncPreferences.setIconVisible(false)
                _events.emit(FilesyncUiEvent.CloseOnboarding)
            }

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
            _uiState.update { it.copy(transferNodeId = node.nodeId, transferProgress = 0f) }
            try {
                val path = repository.download(node, payload) { progress ->
                    _uiState.update { it.copy(transferProgress = progress) }
                }
                if (path == null) {
                    _events.emit(FilesyncUiEvent.Error(FilesyncError.DownloadFailed))
                } else {
                    _events.emit(if (save) FilesyncUiEvent.SaveFile(path, node.name) else FilesyncUiEvent.OpenFile(path))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PayloadDamagedException) {
                Logger.e(e, TAG) { "file ${node.nodeId} failed its hash check" }
                _events.emit(FilesyncUiEvent.Error(FilesyncError.Damaged))
            } catch (e: Exception) {
                Logger.e(e, TAG) { "download of ${node.nodeId} failed" }
                _events.emit(FilesyncUiEvent.Error(FilesyncError.DownloadFailed))
            } finally {
                _uiState.update { it.copy(transferNodeId = null, transferProgress = 0f) }
            }
        }
    }
}
