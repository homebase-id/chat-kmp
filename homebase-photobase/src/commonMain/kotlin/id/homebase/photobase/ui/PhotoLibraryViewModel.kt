package id.homebase.photobase.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.photobase.gallery.LibraryDays
import id.homebase.photobase.gallery.PhotoAlbum
import id.homebase.photobase.gallery.PhotoAlbumSource
import id.homebase.photobase.gallery.PhotoGallery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The galleries the library opens on their own, each with how many photos it holds, counted while shown; and the albums. */
class PhotoLibraryViewModel(
    galleries: List<Pair<PhotoGallery, Flow<LibraryDays>>>,
    private val albumSource: PhotoAlbumSource? = null,
    // The app's: leaving the screen mustn't stop a save halfway. Null runs it on the view model's own.
    private val writeScope: CoroutineScope? = null,
) : ViewModel() {
    // Main only: what each gallery last counted, shown again at once when the screen comes back.
    private val held = mutableMapOf<PhotoGallery, Int>()

    /** Null while a gallery's photos are being counted, or the count failed, and none was held before. */
    val counts: StateFlow<Map<PhotoGallery, Int?>> = combine(
        // Something first, or combine waits for the slowest gallery before showing any count.
        galleries.map { (gallery, days) ->
            days.map<LibraryDays, Int?> { it.photos.also { photos -> held[gallery] = photos } }.onStart { emit(held[gallery]) }
        },
    ) { counted -> galleries.map { it.first }.zip(counted).toMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), galleries.associate { it.first to null })

    /** Null until read. */
    val albums: StateFlow<List<PhotoAlbum>?> = albumSource?.albums ?: MutableStateFlow(null)

    private val _createFailed = Channel<Unit>(Channel.BUFFERED)
    val createFailed: Flow<Unit> = _createFailed.receiveAsFlow()

    /** It shows once the drive's sync brings it back. */
    fun createAlbum(name: String) {
        val source = albumSource ?: return
        (writeScope ?: viewModelScope).launch {
            try {
                source.create(name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(tag = "PhotoLibraryViewModel", throwable = e) { "couldn't make an album" }
                _createFailed.send(Unit)
            }
        }
    }
}
