package id.homebase.photobase.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.common.OdinId
import id.homebase.photobase.gallery.PhotoGalleryItem
import id.homebase.photobase.gallery.toGalleryItem
import id.homebase.photobase.sharing.PhotoAlbumSharing
import id.homebase.photobase.sharing.PhotoAddOutcome
import id.homebase.photobase.sharing.PhotoMemberWrites
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.file.contentType
import id.homebase.core.files.materializeForUpload
import id.homebase.core.files.modifiedAtMs
import id.homebase.core.files.toUploadPath
import id.homebase.core.util.isDesktop
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlin.time.Clock
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.emptyFlow
import id.homebase.photobase.social.PhotoActionService
import id.homebase.photobase.social.PhotoComment
import id.homebase.photobase.social.PhotoCommentsService
import id.homebase.photobase.social.PhotoReactions
import id.homebase.photobase.social.PhotoTarget
import id.homebase.photobase.sharing.SharedAlbum
import kotlin.uuid.Uuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A photo in someone's shared album, as their drive holds it; [addedBy] when a member, not the owner, added it. */
data class SharedPhoto(val file: HomebaseFile, val item: PhotoGalleryItem, val addedBy: OdinId?)

data class SharedAlbumUiState(
    val album: SharedAlbum? = null,
    val photos: List<SharedPhoto> = emptyList(),
    val loading: Boolean = true,
    val allRead: Boolean = false,
    /** In [photos]. */
    val openedIndex: Int? = null,
)

sealed interface SharedAlbumEvent {
    data object Failed : SharedAlbumEvent
    /** A photo picked here wasn't even queued. */
    data object AddFailed : SharedAlbumEvent
    data class Add(val outcome: PhotoAddOutcome) : SharedAlbumEvent
}

/** An album [owner] shares with this identity, read over peer a page at a time and kept in memory. */
class SharedAlbumViewModel(
    val owner: OdinId,
    val albumId: Uuid,
    private val sharing: PhotoAlbumSharing,
    private val memberWrites: PhotoMemberWrites,
    private val fileOps: FileOperationsProvider,
    private val comments: PhotoCommentsService,
    private val actions: PhotoActionService,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SharedAlbumUiState())
    val uiState: StateFlow<SharedAlbumUiState> = _uiState.asStateFlow()

    private val _events = Channel<SharedAlbumEvent>(Channel.BUFFERED)
    val events: Flow<SharedAlbumEvent> = _events.receiveAsFlow()

    private var cursor: String? = null
    private var reading = false
    private var sharerKept = false

    init {
        reload()
        viewModelScope.launch {
            memberWrites.outcomes.filter { it.add.owner == owner && it.add.albumId == albumId }.collect { outcome ->
                _events.send(SharedAlbumEvent.Add(outcome))
                if (outcome is PhotoAddOutcome.Delivered) reload()
            }
        }
    }

    /** Adds [files], picked on this device, to the album; each one's outcome comes as an event. */
    fun addPhotos(files: List<PlatformFile>) {
        val album = _uiState.value.album?.takeIf { it.canAdd } ?: return
        val writes = memberWrites
        val ops = fileOps
        viewModelScope.launch {
            for (file in files) {
                try {
                    // Copied while the picker's grant is live: Android and iOS revoke it later.
                    val path = (if (isDesktop()) file else file.materializeForUpload(ops)).toUploadPath(ops)
                    writes.add(album, path, file.contentType(), file.name, file.modifiedAtMs() ?: Clock.System.now().toEpochMilliseconds())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(tag = TAG, throwable = e) { "a photo for $owner's album $albumId wasn't queued" }
                    _events.send(SharedAlbumEvent.AddFailed)
                }
            }
        }
    }

    fun reload() {
        cursor = null
        _uiState.update { it.copy(photos = emptyList(), allRead = false, loading = true) }
        viewModelScope.launch {
            val album = guarded { sharing.sharedAlbum(owner, albumId) }
            _uiState.update { it.copy(album = album, loading = false, allRead = album == null) }
            if (album != null) {
                loadMore()
                if (!sharerKept) keepSharer()
            }
        }
    }

    /** The next page, when there's more and none is being read. */
    fun loadMore() {
        val album = _uiState.value.album ?: return
        if (reading || _uiState.value.allRead) return
        reading = true
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val (files, next) = guarded { sharing.sharedPhotos(album, cursor) } ?: return@launch
                cursor = next
                val photos = files.mapNotNull { file -> file.toGalleryItem()?.let { SharedPhoto(file, it, file.addedBy()) } }
                _uiState.update { state ->
                    val known = state.photos.mapTo(HashSet()) { it.file.fileId }
                    state.copy(photos = state.photos + photos.filter { it.file.fileId !in known }, allRead = next == null)
                }
            } finally {
                reading = false
                _uiState.update { it.copy(loading = false) }
            }
        }
    }

    // Opened from a chat message, the sharer is kept so the Library finds the album again; the album itself showed either way.
    private suspend fun keepSharer() {
        try {
            sharing.keepSharer(owner)
            sharerKept = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "$owner not kept as a sharer" }
        }
    }

    fun open(index: Int?) = _uiState.update { it.copy(openedIndex = index) }

    fun commentsOf(photoId: Uuid): Flow<List<PhotoComment>> = targetOf(photoId)?.let { comments.commentsOf(it) } ?: emptyFlow()

    fun reactionsOf(photoId: Uuid): Flow<PhotoReactions> = targetOf(photoId)?.let { actions.reactionsOf(it) } ?: emptyFlow()

    fun commentReactionsOf(photoId: Uuid, comment: PhotoComment): Flow<PhotoReactions> =
        targetOf(photoId)?.forComment(comment)?.let { actions.reactionsOf(it) } ?: emptyFlow()

    /** A comment or reaction on one of the album's photos, sent to its owner. */
    fun onSocial(action: PhotoGalleryUiAction) {
        if (action !is PhotoSocialAction) return
        val target = targetOf(action.photoId) ?: return
        viewModelScope.launch { guarded { action.write(target, comments, actions) } }
    }

    // The photo's own key, as its share opened it: comments are encrypted with it.
    private fun targetOf(photoId: Uuid): PhotoTarget? =
        _uiState.value.photos.firstOrNull { it.file.fileMetadata.globalTransitId == photoId }
            ?.let { PhotoTarget(owner = owner, fileId = it.file.fileId, globalTransitId = photoId, keyHeader = it.file.keyHeader) }

    private fun HomebaseFile.addedBy(): OdinId? = fileMetadata.senderOdinId?.takeIf { !it.domainName.equals(owner.domainName, ignoreCase = true) }

    private suspend fun <T> guarded(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(tag = TAG, throwable = e) { "$owner's album $albumId not read" }
            _events.send(SharedAlbumEvent.Failed)
            null
        }

    private companion object {
        const val TAG = "SharedAlbumViewModel"
    }
}
