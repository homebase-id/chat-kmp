package id.homebase.core.ui.screens.filesync

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

enum class FilesyncRecentTab { Added, Deleted }

@Immutable
data class FilesyncRecentUiState(
    val tab: FilesyncRecentTab = FilesyncRecentTab.Added,
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val added: List<FilesyncNode> = emptyList(),
    val deleted: List<FilesyncNode> = emptyList(),
    val folderNames: Map<Uuid, String> = emptyMap(),
    val transfer: FilesyncTransfer? = null,
)

sealed interface FilesyncRecentAction {
    data class SelectTab(val tab: FilesyncRecentTab) : FilesyncRecentAction
    data object Refresh : FilesyncRecentAction
    data class Open(val node: FilesyncNode) : FilesyncRecentAction
    data class Save(val node: FilesyncNode) : FilesyncRecentAction
    data object CancelTransfer : FilesyncRecentAction
    data class SaveHandedOff(val path: String) : FilesyncRecentAction
    data class Restore(val nodeId: Uuid) : FilesyncRecentAction
}

/** Files, one by one, in the order they arrived or were deleted; folders and what a delete took along are Trash's business. */
class FilesyncRecentViewModel(
    private val repository: FilesyncRepository,
    private val editor: FilesyncEditor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesyncRecentUiState())
    val uiState: StateFlow<FilesyncRecentUiState> = _uiState.asStateFlow()

    private val _events = Channel<FilesyncUiEvent>(Channel.BUFFERED)
    val events: Flow<FilesyncUiEvent> = _events.receiveAsFlow()

    private var loadJob: Job? = null
    private val transfers = FilesyncTransfers(
        viewModelScope,
        repository,
        onTransfer = { change -> _uiState.update { it.copy(transfer = change(it.transfer)) } },
        send = _events::send,
    )

    init {
        load()
        viewModelScope.launch { editor.changed.collect { load() } }
    }

    fun onAction(action: FilesyncRecentAction) {
        when (action) {
            is FilesyncRecentAction.SelectTab -> _uiState.update { it.copy(tab = action.tab) }
            FilesyncRecentAction.Refresh -> load()
            is FilesyncRecentAction.Open -> transfers.open(action.node)
            is FilesyncRecentAction.Save -> transfers.save(action.node)
            FilesyncRecentAction.CancelTransfer -> transfers.cancel()
            is FilesyncRecentAction.SaveHandedOff -> repository.discard(action.path)
            is FilesyncRecentAction.Restore -> viewModelScope.launch {
                reportWrite(FilesyncNotice.Restored, FilesyncError.RestoreFailed, _events::send) { editor.restore(setOf(action.nodeId)) }
            }
        }
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadFailed = false) }
            try {
                val (added, deleted) = coroutineScope {
                    val added = async { repository.listRecentlyAdded() }
                    val deleted = async { repository.listDeleted() }
                    added.await() to deleted.await()
                }
                val trash = FilesyncTrash(deleted)
                val addedFiles = added.nodes.values.filter { it.nodeId !in trash.itemOf }
                val deletedFiles = deleted.nodes.values.filter { !it.isFolder && it.removed }.sortedByDescending { it.removedMs }
                val parents = (addedFiles + deletedFiles).mapTo(mutableSetOf()) { it.parentId }
                val names = repository.folderNames(parents, deleted.nodes)
                transfers.handles = added.handles
                _uiState.update { it.copy(isLoading = false, added = addedFiles, deleted = deletedFiles, folderNames = names) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e, TAG) { "listing recent files failed" }
                _uiState.update { it.copy(isLoading = false, loadFailed = true) }
            }
        }
    }

    private companion object {
        const val TAG = "FilesyncRecentViewModel"
    }
}
