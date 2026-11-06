package id.homebase.photobase.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.photobase.gallery.PhotoGalleryDay
import id.homebase.photobase.gallery.PhotoGalleryItem
import id.homebase.photobase.gallery.PhotoGalleryPageSource
import id.homebase.photobase.gallery.groupByCaptureDay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Instant

data class PhotoGalleryUiState(
    val days: List<PhotoGalleryDay> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val loadFailed: Boolean = false,
    val endReached: Boolean = false,
    val newestFirst: Boolean = true,
    val viewer: FullScreenOverlay? = null,
)

sealed interface PhotoGalleryUiAction {
    data object Refresh : PhotoGalleryUiAction
    data object LoadMore : PhotoGalleryUiAction
    data object ToggleSort : PhotoGalleryUiAction
    data class Open(val item: PhotoGalleryItem) : PhotoGalleryUiAction
    data object CloseViewer : PhotoGalleryUiAction
}

/** Queries when it opens and when asked: with no sync or WebSocket, that's how other devices' photos appear. */
class PhotoGalleryViewModel(private val source: PhotoGalleryPageSource, private val grouping: CoroutineDispatcher = Dispatchers.Default) : ViewModel() {
    private val _uiState = MutableStateFlow(PhotoGalleryUiState())
    val uiState: StateFlow<PhotoGalleryUiState> = _uiState.asStateFlow()

    private var items: List<PhotoGalleryItem> = emptyList()
    private var cursor: String? = null
    private var loadJob: Job? = null

    init {
        refresh()
    }

    fun onAction(action: PhotoGalleryUiAction) {
        when (action) {
            PhotoGalleryUiAction.Refresh -> refresh()
            PhotoGalleryUiAction.LoadMore -> loadMore()
            PhotoGalleryUiAction.ToggleSort -> {
                _uiState.update { it.copy(newestFirst = !it.newestFirst) }
                refresh()
            }
            is PhotoGalleryUiAction.Open -> _uiState.update { it.copy(viewer = viewerFor(action.item)) }
            PhotoGalleryUiAction.CloseViewer -> _uiState.update { it.copy(viewer = null) }
        }
    }

    private fun refresh() {
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true, isLoadingMore = false, loadFailed = false) }
        loadJob = viewModelScope.launch { load(cursorToLoad = null) }
    }

    private fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || state.endReached || cursor == null) return
        _uiState.update { it.copy(isLoadingMore = true, loadFailed = false) }
        loadJob = viewModelScope.launch { load(cursorToLoad = cursor) }
    }

    private suspend fun load(cursorToLoad: String?) {
        val newestFirst = _uiState.value.newestFirst
        val page = try {
            source.page(cursorToLoad, newestFirst)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "gallery page failed" }
            _uiState.update { it.copy(isLoading = false, isLoadingMore = false, loadFailed = true) }
            return
        }
        items = if (cursorToLoad == null) page.items else items + page.items
        cursor = page.nextCursor
        val days = withContext(grouping) { groupByCaptureDay(items, newestFirst = newestFirst) }
        _uiState.update {
            it.copy(
                days = days,
                isLoading = false,
                isLoadingMore = false,
                loadFailed = false,
                endReached = page.nextCursor == null,
            )
        }
    }

    private fun viewerFor(item: PhotoGalleryItem): FullScreenOverlay =
        if (item.payload.isVideo()) {
            FullScreenOverlay.VideoPlayerData(
                fileId = item.fileId,
                driveId = item.driveId,
                payloadKey = item.payload.key,
                keyHeader = item.keyHeader,
                payload = item.payload,
            )
        } else {
            FullScreenOverlay.ViewMessageData(
                messageId = item.fileId,
                title = item.name.orEmpty(),
                userDate = Instant.fromEpochMilliseconds(item.capturedAtMs),
                content = "",
                fileId = item.fileId,
                driveId = item.driveId,
                payloads = listOf(item.payload),
                keyHeader = item.keyHeader,
                selectedPayloadKey = item.payload.key,
            )
        }

    private companion object {
        const val TAG = "PhotoGalleryViewModel"
    }
}
