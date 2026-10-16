package id.homebase.core.ui.screens.filesync

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

@Immutable
data class FilesyncTrashUiState(
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
    val items: List<FilesyncTrashItem> = emptyList(),
    val folderNames: Map<Uuid, String> = emptyMap(),
    val selected: Set<Uuid> = emptySet(),
    val busy: Boolean = false,
)

sealed interface FilesyncTrashAction {
    data object Refresh : FilesyncTrashAction
    data class ToggleSelected(val nodeId: Uuid) : FilesyncTrashAction
    data object ClearSelection : FilesyncTrashAction
    data class Restore(val nodeIds: Set<Uuid>) : FilesyncTrashAction
    data class DeleteForever(val nodeIds: Set<Uuid>) : FilesyncTrashAction
    data object EmptyTrash : FilesyncTrashAction
}

class FilesyncTrashViewModel(
    private val repository: FilesyncRepository,
    private val editor: FilesyncEditor,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FilesyncTrashUiState())
    val uiState: StateFlow<FilesyncTrashUiState> = _uiState.asStateFlow()

    private val _events = Channel<FilesyncUiEvent>(Channel.BUFFERED)
    val events: Flow<FilesyncUiEvent> = _events.receiveAsFlow()

    /** What the list shows: Empty and Delete forever touch only these, as they were when listed. */
    private var shown = FilesyncTrash(FilesyncNodes(emptyMap(), emptyMap()))
    private var loadJob: Job? = null

    init {
        load()
        viewModelScope.launch { editor.changed.collect { load() } }
    }

    fun onAction(action: FilesyncTrashAction) {
        when (action) {
            FilesyncTrashAction.Refresh -> load()
            is FilesyncTrashAction.ToggleSelected -> _uiState.update {
                it.copy(selected = if (action.nodeId in it.selected) it.selected - action.nodeId else it.selected + action.nodeId)
            }
            FilesyncTrashAction.ClearSelection -> _uiState.update { it.copy(selected = emptySet()) }
            is FilesyncTrashAction.Restore -> write(FilesyncNotice.Restored, FilesyncError.RestoreFailed) {
                editor.restore(action.nodeIds)
            }
            is FilesyncTrashAction.DeleteForever -> write(FilesyncNotice.DeletedForever, FilesyncError.DeleteFailed) {
                val group = action.nodeIds.mapNotNull { shown.all.nodes[it] }.flatMap { shown.group(it) }
                editor.deleteForGood(shown.all.only(group.mapTo(mutableSetOf()) { it.nodeId }))
            }
            FilesyncTrashAction.EmptyTrash -> write(FilesyncNotice.DeletedForever, FilesyncError.DeleteFailed) {
                editor.deleteForGood(shown.deleted)
            }
        }
    }

    private fun write(done: FilesyncNotice, failed: FilesyncError, block: suspend () -> FilesyncWriteResult) {
        if (_uiState.value.busy) return
        viewModelScope.launch {
            _uiState.update { it.copy(busy = true, selected = emptySet()) }
            try {
                reportWrite(done, failed, _events::send, block)
            } finally {
                _uiState.update { it.copy(busy = false) }
            }
        }
    }

    private fun load() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, loadFailed = false) }
            try {
                val trash = FilesyncTrash(repository.listDeleted())
                val items = trash.items()
                val names = repository.folderNames(items.map { it.node.parentId }.toSet(), trash.all.nodes)
                shown = trash
                _uiState.update { state ->
                    state.copy(
                        isLoading = false,
                        items = items,
                        folderNames = names,
                        selected = state.selected intersect items.mapTo(mutableSetOf()) { it.node.nodeId },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(e, TAG) { "listing the trash failed" }
                _uiState.update { it.copy(isLoading = false, loadFailed = true) }
            }
        }
    }

    private companion object {
        const val TAG = "FilesyncTrashViewModel"
    }
}
