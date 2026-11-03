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
    val albums: ImmutableList<EnteAlbum> = persistentListOf(),
    /** The album being shown; null is the whole library. */
    val album: Uuid? = null,
    val photos: ImmutableList<EntePhoto> = persistentListOf(),
    val selected: ImmutableList<Uuid> = persistentListOf(),
    val loading: Boolean = false,
    val canLoadMore: Boolean = false,
    val loadFailed: Boolean = false,
    val preparing: Boolean = false,
    /** How much of the selection has been decrypted, by size, while [preparing]. */
    val progress: Float = 0f,
    val prepareFailed: Boolean = false,
)

sealed interface EntePhotosEvent {
    /** Decrypted originals, as temp files, in the order they were picked. */
    data class Picked(val paths: List<String>) : EntePhotosEvent
    data object Close : EntePhotosEvent
}

const val MAX_ENTE_SELECTION = 32

class EntePhotosViewModel(
    private val repository: EnteLibrary,
    val extendPermissionViewModel: ExtendPermissionViewModel,
) : ViewModel() {

    private val _uiState = MutableStateFlow(EntePhotosUiState())
    val uiState: StateFlow<EntePhotosUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<EntePhotosEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<EntePhotosEvent> = _events.asSharedFlow()

    private var loadJob: Job? = null
    private var accessJob: Job? = null

    // Photos in hidden albums: never offered, as Ente keeps them out of its own library. Null until read.
    private var hiddenPhotos: Set<Uuid>? = null

    // The library's paging cursor, or an album's members and how many of them are shown.
    private var cursor: String? = null
    private var members: List<Uuid>? = null
    private var shownMembers = 0
    private var exhausted = false

    // Picks survive switching albums, so they're kept apart from what's on screen.
    private val picked = LinkedHashMap<Uuid, EntePhoto>()

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

    /** Each time the picker opens: the library may have changed, and access may have been granted or taken away. */
    fun open() {
        accessJob?.cancel()
        loadJob?.cancel()
        hiddenPhotos = null
        picked.clear()
        resetSource()
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

    /** Shows [album], or the whole library for null. */
    fun showAlbum(album: Uuid?) {
        if (_uiState.value.album == album) return
        loadJob?.cancel()
        resetSource()
        _uiState.update { it.copy(album = album, photos = persistentListOf(), loading = false, canLoadMore = false, loadFailed = false) }
        loadMore()
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.access != EntePhotosAccess.Granted || loadJob?.isActive == true || exhausted) return
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(loading = true, loadFailed = false) }
            try {
                val hidden = hiddenPhotos ?: readAlbums()
                val album = state.album
                val known = _uiState.value.photos.mapTo(HashSet()) { it.fileId }
                // A page can be all hidden photos, or all live photos; keep going until something shows.
                var fresh: List<EntePhoto>
                do {
                    val page = if (album == null) libraryPage() else albumPage(album)
                    // As Ente shows them: archived photos only in their albums, hidden ones nowhere.
                    fresh = page.filter {
                        it.fileId !in known && it.uniqueId !in hidden &&
                            it.visibility != ENTE_HIDDEN && (album != null || it.visibility != ENTE_ARCHIVED)
                    }
                } while (fresh.isEmpty() && !exhausted)
                _uiState.update { s -> s.copy(photos = (s.photos + fresh).toImmutableList(), canLoadMore = !exhausted, loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(tag = TAG, throwable = e) { "listing photos failed" }
                _uiState.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    private suspend fun readAlbums(): Set<Uuid> {
        val albums = repository.albums()
        val hidden = albums.filter { it.hidden }.flatMapTo(HashSet()) { repository.albumMembers(it.id) }
        hiddenPhotos = hidden
        _uiState.update { it.copy(albums = albums.offered().toImmutableList()) }
        return hidden
    }

    private suspend fun libraryPage(): List<EntePhoto> {
        val page = repository.photos(cursor)
        cursor = page.cursor
        exhausted = page.cursor == null
        return page.photos
    }

    private suspend fun albumPage(album: Uuid): List<EntePhoto> {
        val all = members ?: repository.albumMembers(album).also { members = it }
        val next = all.subList(shownMembers, minOf(shownMembers + EnteLibrary.PAGE_SIZE, all.size))
        val photos = repository.photosByUniqueId(next)
        shownMembers += next.size
        exhausted = shownMembers >= all.size
        return photos
    }

    private fun resetSource() {
        cursor = null
        members = null
        shownMembers = 0
        exhausted = false
    }

    fun toggle(photo: EntePhoto) {
        if (photo.tooBigToSend) return
        _uiState.update { s ->
            when {
                photo.fileId in s.selected -> {
                    picked.remove(photo.fileId)
                    s.copy(selected = (s.selected - photo.fileId).toImmutableList())
                }
                s.selected.size < MAX_ENTE_SELECTION -> {
                    picked[photo.fileId] = photo
                    s.copy(selected = (s.selected + photo.fileId).toImmutableList())
                }
                else -> s
            }
        }
    }

    fun send() {
        val state = _uiState.value
        if (state.preparing || state.selected.isEmpty()) return
        val chosen = state.selected.mapNotNull { picked[it] }
        // Weighted by size, so one big video doesn't look done when the photos are.
        val weights = chosen.map { (it.sizeBytes ?: 1L).coerceAtLeast(1L).toDouble() }
        val total = weights.sum()
        viewModelScope.launch {
            _uiState.update { it.copy(preparing = true, progress = 0f, prepareFailed = false) }
            try {
                var done = 0.0
                val paths = chosen.mapIndexedNotNull { i, photo ->
                    repository.exportOriginal(photo) { fraction ->
                        _uiState.update { it.copy(progress = ((done + weights[i] * fraction) / total).toFloat()) }
                    }.also { done += weights[i] }
                }
                if (paths.isEmpty()) {
                    _uiState.update { it.copy(prepareFailed = true) }
                } else {
                    _events.emit(EntePhotosEvent.Picked(paths))
                    picked.clear()
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
