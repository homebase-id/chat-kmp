package id.homebase.photobase.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.common.OdinId
import id.homebase.photobase.gallery.toGalleryItem
import id.homebase.photobase.sharing.PhotoAlbumSharing
import id.homebase.photobase.sharing.PhotoSharingException
import id.homebase.photobase.sharing.SharedAlbum
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import id.homebase.api.client.drives.shares.SelectiveShareCircles
import id.homebase.photobase.sharing.typedIdentityOrNull
import kotlin.uuid.Uuid

/** Why a sharer's albums didn't open. */
enum class SharedProblem { NotReviewed, NotSealedYet, Failed }

/** [albums] null while read, and when [problem] says why they couldn't be; [covers] by album id, each its newest photo. */
data class SharerAlbums(
    val owner: OdinId,
    val albums: List<SharedAlbum>? = null,
    val problem: SharedProblem? = null,
    val covers: Map<Uuid, SharedPhoto> = emptyMap(),
)

data class SharedWithMeUiState(
    /** Null until the sharers are read. */
    val sharers: List<SharerAlbums>? = null,
    val identityInvalid: Boolean = false,
)

/** The albums others share with this identity: one section per sharer kept, each read from their drive. */
class SharedWithMeViewModel(private val sharing: PhotoAlbumSharing) : ViewModel() {
    private val _uiState = MutableStateFlow(SharedWithMeUiState())
    val uiState: StateFlow<SharedWithMeUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val sharers = guarded { sharing.sharers() } ?: return@launch
            _uiState.update { it.copy(sharers = sharers.map { owner -> SharerAlbums(owner) }) }
            // Every read goes through this identity's server to another's: a few at a time.
            val reads = Semaphore(SelectiveShareCircles.PARALLEL)
            sharers.map { owner ->
                async {
                    val read = reads.withPermit { read(owner) }
                    show(read)
                    // The names first: a cover is one more read over peer per album.
                    val albums = read.albums ?: return@async
                    val covers = albums.map { album -> async { reads.withPermit { coverOf(album) }?.let { album.id to it } } }.awaitAll().filterNotNull().toMap()
                    show(read.copy(covers = covers))
                }
            }.awaitAll()
        }
    }

    /** Keeps someone who shares with this identity, typed in, and reads their albums. */
    fun addSharer(text: String) {
        val owner = typedIdentityOrNull(text) ?: return _uiState.update { it.copy(identityInvalid = true) }
        _uiState.update { it.copy(identityInvalid = false) }
        viewModelScope.launch {
            guarded { sharing.keepSharer(owner) } ?: return@launch
            refresh()
        }
    }

    private fun show(sharer: SharerAlbums) =
        _uiState.update { state -> state.copy(sharers = state.sharers?.map { if (it.owner == sharer.owner) sharer else it }) }

    // An album with no cover shows its icon, as an empty one of this identity's does.
    private suspend fun coverOf(album: SharedAlbum): SharedPhoto? =
        try {
            sharing.sharedPhotos(album, cursor = null, pageSize = 1).first.firstOrNull()
                ?.let { file -> file.toGalleryItem()?.let { SharedPhoto(file, it, addedBy = null) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "${album.owner}'s album ${album.id} cover not read" }
            null
        }

    private suspend fun read(owner: OdinId): SharerAlbums =
        try {
            SharerAlbums(owner, albums = sharing.sharedAlbums(owner).sortedBy { it.name.lowercase() })
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhotoSharingException.NotReviewedByOwner) {
            SharerAlbums(owner, problem = SharedProblem.NotReviewed)
        } catch (e: PhotoSharingException.NotSealedYet) {
            SharerAlbums(owner, problem = SharedProblem.NotSealedYet)
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "$owner's albums not read" }
            SharerAlbums(owner, problem = SharedProblem.Failed)
        }

    private suspend fun <T> guarded(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "sharers not read" }
            null
        }

    private companion object {
        const val TAG = "SharedWithMeViewModel"
    }
}
