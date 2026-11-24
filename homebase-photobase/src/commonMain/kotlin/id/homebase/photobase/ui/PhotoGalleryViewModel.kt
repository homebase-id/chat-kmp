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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingCommand
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
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
    val countFailed: Boolean = false,
) {
    val isSyncing: Boolean get() = syncedSoFar != null
}

sealed interface PhotoGalleryUiAction {
    data object Refresh : PhotoGalleryUiAction
    data object ThumbnailShown : PhotoGalleryUiAction
    data object ToggleSort : PhotoGalleryUiAction
    /** Count the library's days again, after a count failed. */
    data object Retry : PhotoGalleryUiAction
    /** The first and last day on screen, in either order. */
    data class Showing(val first: Int, val last: Int) : PhotoGalleryUiAction
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
    touchedDays: Flow<Set<Int>?> = emptyFlow(),
    countFailed: Flow<Boolean> = flowOf(false),
    private val recount: suspend () -> Unit = {},
    // One at a time, off Main: the first screen's composing mustn't hold up reading it. Owns counted and stale.
    reading: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) : ViewModel() {
    private var counted: LibraryDays? = null
    private val stale = mutableSetOf<Int>()
    // The days being read right now: a change to one of them mustn't be lost when the read lands.
    private var inFlight: Set<Int> = emptySet()

    /** Every day of the library that has photos; counted from the gallery's opening, and kept current while it's shown. */
    val library: StateFlow<LibraryDays?> = libraryDays
        .onEach(::onLibrary)
        .flowOn(reading)
        .stateIn(viewModelScope, FromOpeningWhileShown, null)

    private val _uiState = MutableStateFlow(PhotoGalleryUiState())
    val uiState: StateFlow<PhotoGalleryUiState> = _uiState.asStateFlow()

    // Not a data class: setting an equal window again must read again, after a count changed.
    private class Window(val first: Int, val last: Int)

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
        viewModelScope.launch { countFailed.collect { failed -> _uiState.update { it.copy(countFailed = failed) } } }
        // Conflated, not latest: a read can't stop partway through its parse, and the days it brings are nearby.
        viewModelScope.launch(reading) { window.filterNotNull().conflate().collect { read(it) } }
        // A photo replaced on a held day, or one taken away and another added, leaves its count as it was.
        viewModelScope.launch(reading) {
            touchedDays.collect { days ->
                val held = _uiState.value.loaded.keys + inFlight
                stale += if (days == null) held else held intersect days
                window.value = window.value?.let { Window(it.first, it.last) }
            }
        }
    }

    fun onAction(action: PhotoGalleryUiAction) {
        when (action) {
            PhotoGalleryUiAction.Refresh -> refresh()
            PhotoGalleryUiAction.Retry -> viewModelScope.launch { recount() }
            PhotoGalleryUiAction.ThumbnailShown -> logThumbnailShown()
            PhotoGalleryUiAction.ToggleSort -> _uiState.update { it.copy(newestFirst = !it.newestFirst) }
            is PhotoGalleryUiAction.Showing -> window.value = Window(action.first, action.last)
            is PhotoGalleryUiAction.Open -> _uiState.update { it.copy(openedFileId = action.item.fileId) }
            PhotoGalleryUiAction.CloseViewer -> _uiState.update { it.copy(openedFileId = null) }
        }
    }

    // Only held days can be out of date: a held day whose count changed is read again, but keeps what it shows until
    // then, so an open photo doesn't vanish.
    private fun onLibrary(library: LibraryDays) {
        val before = counted
        counted = library
        val held = _uiState.value.loaded.keys
        if (before != null) stale += (held + inFlight).filter { day -> library.countOf(day) != before.countOf(day) }
        val gone = held.filter { library.countOf(day = it) == 0 }
        if (gone.isNotEmpty()) _uiState.update { state -> state.copy(loaded = state.loaded - gone.toSet()) }
        // Before the screen has laid out and said what it shows, the top: the gallery opens there.
        val top = if (library.days.isEmpty()) null else if (_uiState.value.newestFirst) library.days.first() else library.days.last()
        window.value = window.value?.let { Window(it.first, it.last) } ?: top?.let { Window(it, it) }
    }

    private suspend fun read(window: Window) {
        // What onLibrary saw, on this same thread: library.value is set on Main, after it.
        val library = counted ?: return
        val last = library.days.size - 1
        if (last < 0) return
        // Indexes into the index's days, newest first, whichever way the grid runs.
        var from = library.indexAtOrNear(maxOf(window.first, window.last))
        var to = library.indexAtOrNear(minOf(window.first, window.last)).coerceAtLeast(from)
        val centre = library.days[(from + to) / 2]
        // Tinies come in the headers, so reading a little either side paints them before they're scrolled to.
        var newer = 0
        while (from > 0 && newer < MARGIN_PHOTOS) newer += library.counts[--from]
        var older = 0
        while (to < last && older < MARGIN_PHOTOS) older += library.counts[++to]
        val inWindow = (from..to).map { library.days[it] }.toSet()
        val loaded = _uiState.value.loaded
        val wanted = (from..to).filter { library.days[it] !in loaded || library.days[it] in stale }
        // Each unbroken run on its own: one span over a gap would parse the held days in it again.
        for (run in wanted.runs()) {
            val days = run.map { library.days[it] }
            // Before reading, so a change while it reads marks the day again.
            stale -= days.toSet()
            inFlight = days.toSet()
            val read = try {
                source.photosOnDays(days.last(), days.first())
            } catch (e: CancellationException) {
                stale += days
                throw e
            } catch (e: Exception) {
                // The placeholders stay; the next scroll asks again.
                stale += days
                Logger.w(tag = TAG, throwable = e) { "couldn't read the photos of days ${days.last()}..${days.first()}" }
                continue
            } finally {
                inFlight = emptySet()
            }
            val now = counted ?: library
            val arriving = days.filter { now.countOf(it) > 0 }.associateWith { read[it].orEmpty() }
            val ids = arriving.values.flatten().mapTo(HashSet()) { it.fileId }
            _uiState.update { state ->
                // A photo that moved day is held once, on the day it was read on.
                val elsewhere = state.loaded.filterKeys { it !in arriving }.mapValues { (_, items) -> items.filterNot { it.fileId in ids } }
                state.copy(loaded = (elsewhere + arriving).evictedAround(centre, inWindow))
            }
        }
        if (wanted.isEmpty()) return
        if (!firstDaysLogged) {
            firstDaysLogged = true
            Logger.i(tag = TAG) { "first days ready ${opened.elapsedNow()} after opening" }
        }
    }

    // The days farthest from where the screen is go first; the window's days and the open photo's day stay, however
    // many photos they hold.
    private fun Map<Int, List<PhotoGalleryItem>>.evictedAround(centre: Int, window: Set<Int>): Map<Int, List<PhotoGalleryItem>> {
        var held = values.sumOf { it.size }
        if (held <= MAX_HELD_PHOTOS) return this
        val open = _uiState.value.openedFileId
        val kept = toMutableMap()
        for (day in keys.sortedByDescending { abs(it - centre) }) {
            if (held <= MAX_HELD_PHOTOS) break
            if (day in window) continue
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
        // first collects; after that, WhileSubscribed's 5 s.
        val FromOpeningWhileShown = SharingStarted { subscribers ->
            SharingStarted.WhileSubscribed(5_000).command(subscribers).onStart { emit(SharingCommand.START) }
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

// The day's index, or where it would be: the days are newest first.
private fun LibraryDays.indexAtOrNear(day: Int): Int {
    val at = days.asList().binarySearch { day.compareTo(it) }
    return (if (at >= 0) at else -at - 1).coerceIn(0, days.size - 1)
}

private fun List<Int>.runs(): List<IntRange> {
    val runs = mutableListOf<IntRange>()
    var start = firstOrNull() ?: return runs
    for (i in 1..size) {
        if (i == size || this[i] != this[i - 1] + 1) {
            runs += start..this[i - 1]
            if (i < size) start = this[i]
        }
    }
    return runs
}
