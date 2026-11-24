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
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.filterNotNull
import id.homebase.photobase.gallery.syncedSoFar
import id.homebase.photobase.gallery.librarySynced
import id.homebase.photobase.gallery.PhotoChange
import id.homebase.api.sync.DriveState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
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
    /** Records the running drive sync has brought in so far, or null when it isn't running. The grid may already show what's held. */
    val syncedSoFar: Int? = null,
    /** Until then an empty grid doesn't mean nothing is backed up. */
    val librarySynced: Boolean = false,
    val loadFailed: Boolean = false,
    val endReached: Boolean = false,
    val newestFirst: Boolean = true,
    /** The photo open full screen; by id, so a reload while it's open doesn't move to another photo. */
    val openedFileId: Uuid? = null,
) {
    val isSyncing: Boolean get() = syncedSoFar != null
}

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
    changes: Flow<PhotoChange> = emptyFlow(),
    driveState: Flow<DriveState?> = flowOf(DriveState.Completed()),
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
    private var thumbnailsShown = 0

    init {
        reload()
        // After the first page, so the prefetch's reads don't hold it up.
        viewModelScope.launch {
            loadJob?.join()
            startWarm()
        }
        viewModelScope.launch {
            driveState.collect { state ->
                _uiState.update { it.copy(syncedSoFar = state.syncedSoFar(), librarySynced = state.librarySynced()) }
            }
        }
        // A burst of changes reloads once, not once per change, and a finished round among them isn't lost.
        val due = MutableStateFlow<PhotoChange?>(null)
        viewModelScope.launch {
            changes.collect { change -> due.update { if (it == PhotoChange.RoundFinished) it else change } }
        }
        viewModelScope.launch {
            due.filterNotNull().collect {
                val change = due.getAndUpdate { null } ?: return@collect
                // A few files re-read a short grid only: re-reading a long scroll for each would cost seconds of parsing.
                if (change == PhotoChange.RoundFinished || items.size <= FILES_ARRIVED_REREAD) reloadShown()
                startWarm()
                delay(CHANGES_GAP)
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
                    viewModelScope.launch { reloadShown() }
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

    // Reads again as many photos as are showing: the sync writes in change order, not photo order, so new records
    // can land anywhere in them, and an end of the library reached mid-sync is stale. In loadJob, so a sort toggle
    // can cancel it and a load-more can't run alongside it.
    private suspend fun reloadShown() {
        loadJob?.join()
        // Replacing the pages under an open viewer could drop the photo being looked at.
        if (_uiState.value.openedFileId != null) {
            reloadOnClose = true
            return
        }
        val shown = items.size
        val reload = viewModelScope.launch {
            val newestFirst = _uiState.value.newestFirst
            val fresh = mutableListOf<PhotoGalleryItem>()
            var next: String? = null
            try {
                do {
                    val page = source.page(next, newestFirst)
                    fresh += page.items
                    next = page.nextCursor
                } while (next != null && fresh.size < shown)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // What's on screen stays; the next change tries again.
                Logger.w(tag = TAG, throwable = e) { "gallery reload failed" }
                return@launch
            }
            show(fresh, next, newestFirst)
        }
        loadJob = reload
        reload.join()
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
        show(if (cursorToLoad == null) page.items else items + page.items, page.nextCursor, newestFirst)
        if (!firstPageLogged) {
            firstPageLogged = true
            Logger.i(tag = TAG) { "first page ready ${opened.elapsedNow()} after opening" }
        }
    }

    private suspend fun show(loaded: List<PhotoGalleryItem>, nextCursor: String?, newestFirst: Boolean) {
        items = loaded
        cursor = nextCursor
        val days = withContext(grouping) { groupByCaptureDay(loaded, newestFirst = newestFirst) }
        _uiState.update {
            it.copy(
                days = days,
                isLoading = false,
                isLoadingMore = false,
                loadFailed = false,
                endReached = nextCursor == null,
            )
        }
    }

    private companion object {
        const val TAG = "PhotoGalleryViewModel"
        const val FIRST_SCREEN_THUMBNAILS = 12
        const val FILES_ARRIVED_REREAD = 100
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
