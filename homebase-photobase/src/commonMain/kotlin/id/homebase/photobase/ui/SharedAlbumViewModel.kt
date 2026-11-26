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
    /** Every page is read. */
    val end: Boolean = false,
    /** The photo open in the viewer, by its place in [photos]. */
    val opened: Int? = null,
)

sealed interface SharedAlbumEvent {
    data object Failed : SharedAlbumEvent
    data object Added : SharedAlbumEvent
    data object AddFailed : SharedAlbumEvent
    data object AddRetrying : SharedAlbumEvent
    data object AddNotConfirmed : SharedAlbumEvent
}

/**
 * An album [owner] shares with this identity, read over peer a page at a time and kept in memory: the album's record,
 * then the photos with its app tag, each opened with the key its share row locks.
 */
class SharedAlbumViewModel(
    val owner: OdinId,
    val albumId: Uuid,
    private val sharing: PhotoAlbumSharing,
    private val memberWrites: PhotoMemberWrites? = null,
    private val fileOps: FileOperationsProvider? = null,
    private val comments: PhotoCommentsService? = null,
    private val actions: PhotoActionService? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SharedAlbumUiState())
    val uiState: StateFlow<SharedAlbumUiState> = _uiState.asStateFlow()

    private val _events = Channel<SharedAlbumEvent>(Channel.BUFFERED)
    val events: Flow<SharedAlbumEvent> = _events.receiveAsFlow()

    private var cursor: String? = null
    private var reading = false

    init {
        reload()
        memberWrites?.let { writes ->
            viewModelScope.launch {
                writes.outcomes.filter { it.add.owner == owner && it.add.albumId == albumId }.collect { outcome ->
                    when (outcome) {
                        is PhotoAddOutcome.Delivered -> _events.send(SharedAlbumEvent.Added).also { reload() }
                        is PhotoAddOutcome.Retrying -> _events.send(SharedAlbumEvent.AddRetrying)
                        is PhotoAddOutcome.Refused -> _events.send(SharedAlbumEvent.AddFailed)
                        is PhotoAddOutcome.NotConfirmed -> _events.send(SharedAlbumEvent.AddNotConfirmed)
                    }
                }
            }
        }
    }

    /** Adds [files], picked on this device, to the album; each one's outcome comes as an event. */
    fun addPhotos(files: List<PlatformFile>) {
        val album = _uiState.value.album?.takeIf { it.canAdd } ?: return
        val writes = memberWrites ?: return
        val ops = fileOps ?: return
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
        _uiState.update { it.copy(photos = emptyList(), end = false, loading = true) }
        viewModelScope.launch {
            val album = guarded { sharing.sharedAlbums(owner).firstOrNull { it.id == albumId } }
            _uiState.update { it.copy(album = album, loading = false, end = album == null) }
            if (album != null) loadMore()
        }
    }

    /** The next page, when there's more and none is being read. */
    fun loadMore() {
        val album = _uiState.value.album ?: return
        if (reading || _uiState.value.end) return
        reading = true
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                val (files, next) = guarded { sharing.sharedPhotos(album, cursor) } ?: return@launch
                cursor = next
                val photos = files.mapNotNull { file -> file.toGalleryItem()?.let { SharedPhoto(file, it, file.addedBy()) } }
                _uiState.update { state ->
                    val known = state.photos.mapTo(HashSet()) { it.file.fileId }
                    state.copy(photos = state.photos + photos.filter { it.file.fileId !in known }, end = next == null)
                }
            } finally {
                reading = false
                _uiState.update { it.copy(loading = false) }
            }
        }
    }

    fun open(index: Int?) = _uiState.update { it.copy(opened = index) }

    val canComment: Boolean get() = comments != null && actions != null

    fun commentsOf(photoId: Uuid): Flow<List<PhotoComment>> = targetOf(photoId)?.let { comments?.commentsOf(it) } ?: emptyFlow()

    fun reactionsOf(photoId: Uuid): Flow<PhotoReactions> = targetOf(photoId)?.let { actions?.reactionsOf(it) } ?: emptyFlow()

    /** A comment or reaction on one of the album's photos, sent to its owner. */
    fun onSocial(action: PhotoGalleryUiAction) {
        val write: suspend (PhotoTarget) -> Unit = when (action) {
            is PhotoGalleryUiAction.PostComment -> { target -> comments?.post(target, action.body.trim()) }
            is PhotoGalleryUiAction.ToggleReaction -> { target -> actions?.toggleReaction(target, action.emoji) }
            else -> return
        }
        val target = when (action) {
            is PhotoGalleryUiAction.PostComment -> targetOf(action.photoId)
            is PhotoGalleryUiAction.ToggleReaction -> targetOf(action.photoId)
            else -> null
        } ?: return
        viewModelScope.launch { guarded { write(target) } }
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
