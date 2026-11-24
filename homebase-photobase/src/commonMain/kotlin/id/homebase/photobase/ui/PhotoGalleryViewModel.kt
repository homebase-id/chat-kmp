package id.homebase.photobase.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.sync.DriveState
import id.homebase.chat.conversationlist.FullScreenOverlay
import id.homebase.photobase.gallery.LibraryDays
import id.homebase.photobase.gallery.PhotoGalleryItem
import id.homebase.photobase.gallery.PhotoGallerySource
import id.homebase.photobase.gallery.librarySynced
import id.homebase.photobase.gallery.syncedSoFar
import kotlin.math.abs
import kotlin.time.Instant
import kotlin.time.TimeSource
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PhotoGalleryUiState(
    /** Records the running drive sync has brought in so far, or null when it isn't running. The grid may already show what's held. */
    val syncedSoFar: Int? = null,
    /** Until then an empty grid doesn't mean nothing is backed up. */
    val librarySynced: Boolean = false,
    val newestFirst: Boolean = true,
    /** The photo open full screen; by id, so photos arriving while it's open don't move it. */
    val openedFileId: Uuid? = null,
    /** The days whose headers are read, by day, newest first within each. */
    val loaded: Map<Int, List<PhotoGalleryItem>> = emptyMap(),
) {
    val isSyncing: Boolean get() = syncedSoFar != null
}

sealed interface PhotoGalleryUiAction {
    data object Refresh : PhotoGalleryUiAction
    data object ThumbnailShown : PhotoGalleryUiAction
    data object ToggleSort : PhotoGalleryUiAction
    /** The days on screen, as positions in the grid's order. */
    data class Showing(val positions: IntRange) : PhotoGalleryUiAction
    /** Tapped in the grid, or swiped to in the viewer. */
    data class Open(val item: PhotoGalleryItem) : PhotoGalleryUiAction
    data object CloseViewer : PhotoGalleryUiAction
}

/**
 * The library's days from [libraryDays], and the headers of the days on screen and a margin around them from [source].
 * A day whose count changes is read again; [refresh] asks the drive to sync now.
 */
class PhotoGalleryViewModel(
    private val source: PhotoGallerySource,
    private val refresh: () -> Unit = {},
    driveState: Flow<DriveState?> = flowOf(DriveState.Completed()),
    libraryDays: Flow<LibraryDays> = emptyFlow(),
    // One at a time, off Main: the first screen's composing mustn't hold up reading it. Owns counted and stale.
    reading: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : ViewModel() {
    private var counted: Map<Int, Int> = emptyMap()
    private val stale = mutableSetOf<Int>()

    /** Every day of the library that has photos; counted from the gallery's opening, and kept current while it's shown. */
    val library: StateFlow<LibraryDays?> = libraryDays
        .onEach(::onLibrary)
        .flowOn(reading)
        .stateIn(viewModelScope, FromOpeningWhileShown, null)

    private val _uiState = MutableStateFlow(PhotoGalleryUiState())
    val uiState: StateFlow<PhotoGalleryUiState> = _uiState.asStateFlow()

    private data class Window(val positions: IntRange, val generation: Int)

    private val window = MutableStateFlow<Window?>(null)
    private val opened = TimeSource.Monotonic.markNow()
    private var firstDaysLogged = false
    private var thumbnailsShown = 0

    init {
        viewModelScope.launch {
            driveState.collect { state ->
                _uiState.update { it.copy(syncedSoFar = state.syncedSoFar(), librarySynced = state.librarySynced()) }
            }
        }
        // A newer window replaces one still being read: it's where the screen is now.
        viewModelScope.launch(reading) { window.filterNotNull().collectLatest { read(it.positions) } }
    }

    fun onAction(action: PhotoGalleryUiAction) {
        when (action) {
            PhotoGalleryUiAction.Refresh -> refresh()
            PhotoGalleryUiAction.ThumbnailShown -> logThumbnailShown()
            PhotoGalleryUiAction.ToggleSort -> {
                _uiState.update { it.copy(newestFirst = !it.newestFirst) }
                // The same positions are other days now.
                window.update { it?.copy(generation = it.generation + 1) }
            }
            is PhotoGalleryUiAction.Showing -> window.update { Window(action.positions, it?.generation ?: 0) }
            is PhotoGalleryUiAction.Open -> _uiState.update { it.copy(openedFileId = action.item.fileId) }
            PhotoGalleryUiAction.CloseViewer -> _uiState.update { it.copy(openedFileId = null) }
        }
    }

    // A day whose count changed is read again, but keeps what it shows until then: an open photo mustn't vanish.
    private fun onLibrary(library: LibraryDays) {
        val now = library.days.indices.associate { library.days[it] to library.counts[it] }
        stale += now.keys.filter { day -> counted[day] != null && counted[day] != now[day] }
        val gone = counted.keys - now.keys
        counted = now
        if (gone.isNotEmpty()) _uiState.update { state -> state.copy(loaded = state.loaded - gone) }
        // The top first, before the screen has laid out and said what it shows: the gallery opens there.
        window.update { it?.copy(generation = it.generation + 1) ?: Window(0..0, 0) }
    }

    private suspend fun read(positions: IntRange) {
        val library = library.value ?: return
        val newestFirst = _uiState.value.newestFirst
        val order = { position: Int -> if (newestFirst) position else library.days.size - 1 - position }
        val count = { position: Int -> library.counts[order(position)] }
        val last = library.days.size - 1
        if (last < 0) return
        var from = positions.first.coerceIn(0, last)
        var to = positions.last.coerceIn(from, last)
        // Tinies come in the headers, so reading a little either side paints them before they're scrolled to.
        var above = 0
        while (from > 0 && above < MARGIN_PHOTOS) above += count(--from)
        var below = 0
        while (to < last && below < MARGIN_PHOTOS) below += count(++to)
        val loaded = _uiState.value.loaded
        val wanted = (from..to).map { library.days[order(it)] }.filter { it !in loaded || it in stale }
        if (wanted.isEmpty()) return
        val read = try {
            source.photosOnDays(wanted.min(), wanted.max())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The placeholders stay; the next scroll asks again.
            Logger.w(tag = TAG, throwable = e) { "couldn't read the photos of days ${wanted.min()}..${wanted.max()}" }
            return
        }
        stale -= wanted.toSet()
        val centre = library.days[order((positions.first + positions.last) / 2)]
        _uiState.update { state ->
            val merged = state.loaded + wanted.associateWith { read[it].orEmpty() }
            state.copy(loaded = merged.evictedAround(centre))
        }
        if (!firstDaysLogged) {
            firstDaysLogged = true
            Logger.i(tag = TAG) { "first days ready ${opened.elapsedNow()} after opening" }
        }
    }

    // The days farthest from where the screen is go first; the open photo's day stays.
    private fun Map<Int, List<PhotoGalleryItem>>.evictedAround(centre: Int): Map<Int, List<PhotoGalleryItem>> {
        var held = values.sumOf { it.size }
        if (held <= MAX_HELD_PHOTOS) return this
        val open = _uiState.value.openedFileId
        val kept = toMutableMap()
        for (day in keys.sortedByDescending { abs(it - centre) }) {
            if (held <= MAX_HELD_PHOTOS) break
            val items = kept.getValue(day)
            if (open != null && items.any { it.fileId == open }) continue
            kept.remove(day)
            held -= items.size
        }
        return kept
    }

    private fun logThumbnailShown() {
        thumbnailsShown++
        if (thumbnailsShown == 1 || thumbnailsShown == FIRST_SCREEN_THUMBNAILS) {
            Logger.i(tag = TAG) { "thumbnail $thumbnailsShown shown ${opened.elapsedNow()} after opening" }
        }
    }

    private companion object {
        // The view model is made when the gallery opens, so counting starts then, not a frame later when the screen
        // first collects; it stops 5 s after the screen leaves, as WhileSubscribed does.
        val FromOpeningWhileShown = SharingStarted { subscribers ->
            subscribers
                .transformLatest { count ->
                    if (count > 0) {
                        emit(SharingCommand.START)
                    } else {
                        delay(5_000)
                        emit(SharingCommand.STOP)
                    }
                }
                .onStart { emit(SharingCommand.START) }
                .distinctUntilChanged()
        }

        const val TAG = "PhotoGalleryViewModel"
        const val FIRST_SCREEN_THUMBNAILS = 12
        // About two phone screens each way.
        const val MARGIN_PHOTOS = 80
        // A few dozen screens: parsing them again costs more than holding them.
        const val MAX_HELD_PHOTOS = 1_000
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
