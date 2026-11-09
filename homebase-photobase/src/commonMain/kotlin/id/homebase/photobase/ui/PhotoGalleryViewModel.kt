package id.homebase.photobase.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.photobase.gallery.PhotoGalleryDay
import id.homebase.photobase.gallery.PhotoGalleryItem
import id.homebase.photobase.gallery.PhotoGalleryPageSource
import id.homebase.photobase.gallery.groupByCaptureDay
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Instant

data class PhotoGalleryUiState(
    val days: List<PhotoGalleryDay> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** Fetching from the server after an open or a refresh; the grid may already show what's held. */
    val isSyncing: Boolean = false,
    val loadFailed: Boolean = false,
    val endReached: Boolean = false,
    val newestFirst: Boolean = true,
    val viewer: FullScreenOverlay? = null,
)

sealed interface PhotoGalleryUiAction {
    data object Refresh : PhotoGalleryUiAction
    data object LoadMore : PhotoGalleryUiAction
    data object ThumbnailShown : PhotoGalleryUiAction
    data object ToggleSort : PhotoGalleryUiAction
    data class Scrolling(val scrolling: Boolean) : PhotoGalleryUiAction
    data class Open(val item: PhotoGalleryItem) : PhotoGalleryUiAction
    data object CloseViewer : PhotoGalleryUiAction
}

/**
 * Shows what the device holds straight away, then catches up when it opens, when asked, and when this
 * phone backs up more: with no WebSocket, that's how new photos appear. [catchUp] returning fetches
 * the first page again.
 */
class PhotoGalleryViewModel(
    private val source: PhotoGalleryPageSource,
    private val grouping: CoroutineDispatcher = Dispatchers.Default,
    private val catchUp: (suspend () -> Unit)? = null,
    private val warm: suspend () -> Unit = {},
    private val pauseWarm: (Boolean) -> Unit = {},
    backedUpCount: Flow<Long>? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PhotoGalleryUiState())
    val uiState: StateFlow<PhotoGalleryUiState> = _uiState.asStateFlow()

    private var items: List<PhotoGalleryItem> = emptyList()
    private var cursor: String? = null
    private var loadJob: Job? = null
    private var catchUpJob: Job? = null
    private var warmJob: Job? = null
    private val opened = TimeSource.Monotonic.markNow()
    private var firstPageLogged = false
    private var pagedPastFirst = false
    private var thumbnailsShown = 0

    init {
        refresh()
        if (backedUpCount != null) {
            viewModelScope.launch {
                // Conflated: a backup ticking per photo queues one more catch-up, not one per photo.
                backedUpCount.distinctUntilChanged().drop(1).conflate().collect {
                    catchUpJob?.join()
                    startCatchUp(showProgress = false).join()
                }
            }
        }
    }

    override fun onCleared() {
        pauseWarm(false)
    }

    fun onAction(action: PhotoGalleryUiAction) {
        when (action) {
            PhotoGalleryUiAction.Refresh -> refresh()
            PhotoGalleryUiAction.LoadMore -> loadMore()
            PhotoGalleryUiAction.ThumbnailShown -> logThumbnailShown()
            is PhotoGalleryUiAction.Scrolling -> pauseWarm(action.scrolling)
            PhotoGalleryUiAction.ToggleSort -> {
                _uiState.update { it.copy(newestFirst = !it.newestFirst) }
                reload()
            }
            is PhotoGalleryUiAction.Open -> _uiState.update { it.copy(viewer = viewerFor(action.item)) }
            PhotoGalleryUiAction.CloseViewer -> _uiState.update { it.copy(viewer = null) }
        }
    }

    private fun logThumbnailShown() {
        thumbnailsShown++
        if (thumbnailsShown == 1 || thumbnailsShown == FIRST_SCREEN_THUMBNAILS) {
            Logger.i(tag = TAG) { "thumbnail $thumbnailsShown shown ${opened.elapsedNow()} after opening" }
        }
    }

    private fun refresh() {
        reload()
        startCatchUp(showProgress = true)
    }

    private fun startCatchUp(showProgress: Boolean): Job {
        catchUpJob?.cancel()
        // Lazy so catchUpJob is set before the body runs: Main.immediate would otherwise run it first.
        return viewModelScope.launch(start = CoroutineStart.LAZY) { catchUpThenReload(showProgress) }
            .also { catchUpJob = it; it.start() }
    }

    private fun reload() {
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true, isLoadingMore = false, loadFailed = false) }
        loadJob = viewModelScope.launch { load(cursorToLoad = null) }
    }

    // Offline is normal here: what's on screen stays, and the next open or refresh tries again.
    private suspend fun catchUpThenReload(showProgress: Boolean) {
        // Waits for the first page, so the grid shows what's held before any of this runs.
        loadJob?.join()
        val catchUp = catchUp ?: return
        val self = currentCoroutineContext()[Job]
        if (showProgress) _uiState.update { it.copy(isSyncing = true) }
        try {
            catchUp()
            // Past the first page, or oldest first, a reload would change what's on screen for nothing.
            // It goes in loadJob so a sort toggle can cancel it, and a load-more can't run alongside it.
            loadJob?.join()
            if (!pagedPastFirst && _uiState.value.newestFirst) {
                val reload = viewModelScope.launch { load(cursorToLoad = null) }
                loadJob = reload
                reload.join()
            }
            // Its own job: a prefetch that waits out a scroll mustn't hold up the next catch-up.
            if (warmJob?.isActive != true) warmJob = viewModelScope.launch { runCatchingWarm() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "gallery catch-up failed" }
        } finally {
            // A refresh that replaced this one owns the flag now.
            if (showProgress && catchUpJob === self) _uiState.update { it.copy(isSyncing = false) }
        }
    }

    private suspend fun runCatchingWarm() {
        try {
            warm()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "gallery prefetch failed" }
        }
    }

    private fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || state.endReached || cursor == null) return
        if (loadJob?.isActive == true) return
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
        pagedPastFirst = cursorToLoad != null
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
        if (!firstPageLogged) {
            firstPageLogged = true
            Logger.i(tag = TAG) { "first page ready ${opened.elapsedNow()} after opening" }
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
        const val FIRST_SCREEN_THUMBNAILS = 12
    }
}
