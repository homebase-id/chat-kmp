package id.homebase.chat.entephotos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.chat.conversationlist.ExtendPermissionUiState
import id.homebase.chat.conversationlist.ExtendPermissionViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

enum class EntePhotosAccess { Checking, NotInstalled, NeedsAccess, Locked, Granted, Failed }

data class EntePhotosUiState(
    val access: EntePhotosAccess = EntePhotosAccess.Checking,
    val photos: ImmutableList<EntePhoto> = persistentListOf(),
    val selected: ImmutableList<Uuid> = persistentListOf(),
    val loading: Boolean = false,
    val canLoadMore: Boolean = false,
    val loadFailed: Boolean = false,
    val preparing: Boolean = false,
    val prepareFailed: Boolean = false,
)

sealed interface EntePhotosEvent {
    /** Decrypted originals, as temp files, in the order they were picked. */
    data class Picked(val paths: List<String>) : EntePhotosEvent
    data object Close : EntePhotosEvent
}

const val MAX_ENTE_SELECTION = 32

class EntePhotosViewModel(
    private val repository: EntePhotosRepository,
    val extendPermissionViewModel: ExtendPermissionViewModel,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EntePhotosUiState())
    val uiState: StateFlow<EntePhotosUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<EntePhotosEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<EntePhotosEvent> = _events.asSharedFlow()

    private var cursor: String? = null
    private var loadJob: Job? = null
    private var accessJob: Job? = null

    init {
        addCloseable(extendPermissionViewModel)
        viewModelScope.launch {
            extendPermissionViewModel.permissionsGranted.filter { it }.collect {
                if (_uiState.value.access == EntePhotosAccess.NeedsAccess || _uiState.value.access == EntePhotosAccess.Checking) onGranted()
            }
        }
        viewModelScope.launch {
            extendPermissionViewModel.uiState.collect { state ->
                if (state is ExtendPermissionUiState.ShowDialog) _uiState.update { it.copy(access = EntePhotosAccess.NeedsAccess) }
            }
        }
        viewModelScope.launch {
            extendPermissionViewModel.checkFailed.filter { it }.collect { _uiState.update { it.copy(access = EntePhotosAccess.Failed) } }
        }
        viewModelScope.launch {
            extendPermissionViewModel.navigateAwayRequest.collect { _events.emit(EntePhotosEvent.Close) }
        }
    }

    /** Each time the picker opens: the photos may have changed, and access may have been granted or taken away. */
    fun open() {
        accessJob?.cancel()
        loadJob?.cancel()
        cursor = null
        _uiState.value = EntePhotosUiState()
        accessJob = viewModelScope.launch {
            try {
                if (!repository.isEnteInstalled()) {
                    _uiState.update { it.copy(access = EntePhotosAccess.NotInstalled) }
                    return@launch
                }
                if (extendPermissionViewModel.permissionsGranted.value) onGranted() else extendPermissionViewModel.recheckPermissions()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(tag = TAG, throwable = e) { "access check failed" }
                _uiState.update { it.copy(access = EntePhotosAccess.Failed) }
            }
        }
    }

    fun askForAccess() = extendPermissionViewModel.recheckPermissions()

    private fun onGranted() {
        viewModelScope.launch {
            val opened = runCatching { repository.openDriveKey() }.getOrDefault(false)
            if (!opened) {
                _uiState.update { it.copy(access = EntePhotosAccess.Locked) }
                return@launch
            }
            _uiState.update { it.copy(access = EntePhotosAccess.Granted) }
            loadMore()
        }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.access != EntePhotosAccess.Granted || loadJob?.isActive == true) return
        if (state.photos.isNotEmpty() && cursor == null) return
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, loadFailed = false) }
            try {
                val page = repository.photos(cursor)
                cursor = page.cursor
                _uiState.update { s ->
                    val known = s.photos.mapTo(HashSet()) { it.fileId }
                    s.copy(
                        photos = (s.photos + page.photos.filter { it.fileId !in known }).toImmutableList(),
                        canLoadMore = page.cursor != null,
                        loading = false,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(tag = TAG, throwable = e) { "listing photos failed" }
                _uiState.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    fun toggle(fileId: Uuid) = _uiState.update { s ->
        when {
            fileId in s.selected -> s.copy(selected = (s.selected - fileId).toImmutableList())
            s.selected.size < MAX_ENTE_SELECTION -> s.copy(selected = (s.selected + fileId).toImmutableList())
            else -> s
        }
    }

    fun send() {
        val state = _uiState.value
        if (state.preparing || state.selected.isEmpty()) return
        val byId = state.photos.associateBy { it.fileId }
        val picked = state.selected.mapNotNull { byId[it] }
        viewModelScope.launch {
            _uiState.update { it.copy(preparing = true, prepareFailed = false) }
            try {
                val paths = picked.mapNotNull { repository.exportOriginal(it) }
                if (paths.isEmpty()) {
                    _uiState.update { it.copy(prepareFailed = true) }
                } else {
                    _events.emit(EntePhotosEvent.Picked(paths))
                    _uiState.update { it.copy(selected = persistentListOf()) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(tag = TAG, throwable = e) { "decrypting originals failed" }
                _uiState.update { it.copy(prepareFailed = true) }
            } finally {
                _uiState.update { it.copy(preparing = false) }
            }
        }
    }

    private companion object {
        const val TAG = "EntePhotos"
    }
}
