package id.homebase.photobase.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.photobase.gallery.PhotoGalleryDay
import id.homebase.photobase.gallery.PhotoGalleryItem
import id.homebase.photobase.gallery.PhotoGalleryPageSource
import id.homebase.photobase.gallery.groupByCaptureDay
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class PhotoGalleryUiState(
    val days: List<PhotoGalleryDay> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    /** The drive sync is running; the grid may already show what's held. */
    val isSyncing: Boolean = false,
    /** Records the running sync has brought in so far. */
    val syncedSoFar: Int = 0,
    val loadFailed: Boolean = false,
    val endReached: Boolean = false,
    val newestFirst: Boolean = true,
    /** The photo open full screen; by id, so a reload while it's open doesn't move to another photo. */
    val openedFileId: Uuid? = null,
)

sealed interface PhotoGalleryUiAction {
    data object Refresh : PhotoGalleryUiAction
    data object LoadMore : PhotoGalleryUiAction
    data object ThumbnailShown : PhotoGalleryUiAction
    data object ToggleSort : PhotoGalleryUiAction
    data class Scrolling(val scrolling: Boolean) : PhotoGalleryUiAction
    /** Tapped in the grid, or swiped to in the viewer. */
    data class Open(val item: PhotoGalleryItem) : PhotoGalleryUiAction
    data object CloseViewer : PhotoGalleryUiAction
}

/**
 * Shows what the device holds. The drive sync keeps that current: [changes] fires when it has brought
 * in new records, and [syncProgress] is how many the running sync has brought in, or null when it isn't
 * running. [refresh] asks it to sync now.
 */
class PhotoGalleryViewModel(
    private val source: PhotoGalleryPageSource,
    private val grouping: CoroutineDispatcher = Dispatchers.Default,
    private val refresh: () -> Unit = {},
    private val warm: suspend () -> Unit = {},
    private val pauseWarm: (Boolean) -> Unit = {},
    changes: Flow<Unit>? = null,
    syncProgress: Flow<Int?>? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(PhotoGalleryUiState())
    val uiState: StateFlow<PhotoGalleryUiState> = _uiState.asStateFlow()

    private var items: List<PhotoGalleryItem> = emptyList()
    private var cursor: String? = null
    private var loadJob: Job? = null
    private var warmJob: Job? = null
    private var reloadOnClose = false
    private val opened = TimeSource.Monotonic.markNow()
    private var firstPageLogged = false
    private var pagedPastFirst = false
    private var thumbnailsShown = 0

    init {
        reload()
        // After the first page, so the prefetch's reads don't hold it up.
        viewModelScope.launch {
            loadJob?.join()
            startWarm()
        }
        if (syncProgress != null) {
            viewModelScope.launch {
                syncProgress.collect { count -> _uiState.update { it.copy(isSyncing = count != null, syncedSoFar = count ?: 0) } }
            }
        }
        if (changes != null) {
            viewModelScope.launch {
                // Conflated: a burst of changes reloads once, not once per change.
                changes.conflate().collect {
                    reloadFirstPage()
                    startWarm()
                    delay(CHANGES_GAP)
                }
            }
        }
    }

    override fun onCleared() {
        pauseWarm(false)
    }

    fun onAction(action: PhotoGalleryUiAction) {
        when (action) {
            PhotoGalleryUiAction.Refresh -> {
                reload()
                refresh()
            }
            PhotoGalleryUiAction.LoadMore -> loadMore()
            PhotoGalleryUiAction.ThumbnailShown -> logThumbnailShown()
            is PhotoGalleryUiAction.Scrolling -> pauseWarm(action.scrolling)
            PhotoGalleryUiAction.ToggleSort -> {
                _uiState.update { it.copy(newestFirst = !it.newestFirst) }
                reload()
            }
            is PhotoGalleryUiAction.Open -> _uiState.update { it.copy(openedFileId = action.item.fileId) }
            PhotoGalleryUiAction.CloseViewer -> {
                _uiState.update { it.copy(openedFileId = null) }
                if (reloadOnClose) {
                    reloadOnClose = false
                    if (!pagedPastFirst && _uiState.value.newestFirst) reload()
                }
            }
        }
    }

    private fun logThumbnailShown() {
        thumbnailsShown++
        if (thumbnailsShown == 1 || thumbnailsShown == FIRST_SCREEN_THUMBNAILS) {
            Logger.i(tag = TAG) { "thumbnail $thumbnailsShown shown ${opened.elapsedNow()} after opening" }
        }
    }

    private fun reload() {
        loadJob?.cancel()
        _uiState.update { it.copy(isLoading = true, isLoadingMore = false, loadFailed = false) }
        loadJob = viewModelScope.launch { load(cursorToLoad = null) }
    }

    // Past the first page, or oldest first, a reload would change what's on screen for nothing.
    // It goes in loadJob so a sort toggle can cancel it, and a load-more can't run alongside it.
    private suspend fun reloadFirstPage() {
        loadJob?.join()
        // Replacing the first page under an open viewer could drop the photo being looked at.
        if (_uiState.value.openedFileId != null) {
            reloadOnClose = true
        } else if (!pagedPastFirst && _uiState.value.newestFirst) {
            val reload = viewModelScope.launch { load(cursorToLoad = null) }
            loadJob = reload
            reload.join()
        }
    }

    // Its own job: a prefetch that waits out a scroll mustn't hold up the next reload.
    private fun startWarm() {
        if (warmJob?.isActive != true) warmJob = viewModelScope.launch { runCatchingWarm() }
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

    private companion object {
        const val TAG = "PhotoGalleryViewModel"
        const val FIRST_SCREEN_THUMBNAILS = 12
        val CHANGES_GAP = 2.seconds
    }
}

internal fun PhotoGalleryItem.toViewer(): FullScreenOverlay =
    if (payload.isVideo()) {
        player(shown)
    } else {
        FullScreenOverlay.ViewMessageData(
            messageId = fileId,
            title = payload.filename().orEmpty(),
            userDate = Instant.fromEpochMilliseconds(capturedAtMs),
            content = "",
            fileId = fileId,
            driveId = driveId,
            payloads = listOf(payload),
            keyHeader = keyHeader,
            selectedPayloadKey = payload.key,
            motionClips = motion?.let { mapOf(payload.key to it) }.orEmpty(),
        )
    }

private fun PhotoGalleryItem.player(video: PayloadDescriptor) = FullScreenOverlay.VideoPlayerData(
    fileId = fileId,
    driveId = driveId,
    payloadKey = video.key,
    // The player decrypts with exactly this header, and each payload has its own IV.
    keyHeader = video.iv?.let { KeyHeader(iv = it.bytes, aesKey = keyHeader.aesKey) } ?: keyHeader,
    payload = video,
)
