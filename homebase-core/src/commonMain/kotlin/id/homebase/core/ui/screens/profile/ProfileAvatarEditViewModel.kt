@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.profile.PreviewThumbnail
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfilePhotoTooLargeException
import id.homebase.api.client.profile.ProfileRepository
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.client.profile.isPhotoScopedTo
import id.homebase.api.client.websockets.PublicProfileArtifact
import id.homebase.api.common.OdinId
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageFormat
import id.homebase.api.image.ImageUtils
import id.homebase.api.image.ThumbnailInstruction
import id.homebase.api.image.createImageThumbnail
import id.homebase.api.image.tinyThumbSize
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.core.clipboard.platformFileFromPath
import id.homebase.core.ui.screens.card.CardCircle
import id.homebase.imageeditor.ui.CropResultBus
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

interface AvatarOwnerSession {
    val user: Flow<OwnerSession?>
    suspend fun reloadAvatar(odinId: OdinId)
}

class OwnerSessionAvatar(private val repository: OwnerSessionRepository) : AvatarOwnerSession {
    override val user: Flow<OwnerSession?> get() = repository.user
    override suspend fun reloadAvatar(odinId: OdinId) =
        repository.reloadAfterOwnPublish(odinId, PublicProfileArtifact.ProfileImage)
}

private data class PhotoSlot(val visibility: ProfileVisibility, val circleId: String? = null)

private const val TAG = "ProfileAvatarEditViewModel"

/** Flip once odin-core#1928 (SetPhotoAttributeRequest.circleIds) ships; until then a circle photo would be stored visible to every connection. */
const val SERVER_STORES_CIRCLE_PHOTOS = false

/**
 * These are profile-photo-sized images, not general media — the API contract calls out a
 * 200×200 rendition landing around 10–20KB and explicitly says not to send anything approaching
 * six figures of bytes. [AVATAR_FULL_SIZE_INSTRUCTION] caps the primary `content` the same way;
 * the crop screen itself imposes no resolution limit, so without this a crop of a large source
 * photo would upload uncapped (multi-MB) as "full size".
 */
private val AVATAR_FULL_SIZE_INSTRUCTION = ThumbnailInstruction(
    quality = 85,
    maxPixelDimension = 800,
    maxBytes = 80_000,
    type = ImageFormat.WEBP,
)

/** Matches the API doc's 200×200 example, capped well under six figures of bytes. */
private val AVATAR_THUMBNAIL_INSTRUCTION = ThumbnailInstruction(
    quality = 80,
    maxPixelDimension = 200,
    maxBytes = 20_000,
    type = ImageFormat.WEBP,
)

/**
 * Drives the dedicated avatar-edit screen: two independent photo slots (Anonymous, Only me).
 * Each slot's flow is pick → crop (via the same [id.homebase.imageeditor.ui.CropScreen] chat
 * attachments use, locked to a square aspect) → upload via
 * `PUT /api/v2/profile/attributes/photo` ([ProfileRepository.uploadPhoto]) → delete.
 */
class ProfileAvatarEditViewModel(
    private val ownerSession: AvatarOwnerSession,
    private val profileRepository: ProfileRepository,
    private val cropResultBus: CropResultBus,
    private val fileOperationsProvider: FileOperationsProvider,
    private val circlePhotosSupported: Boolean = SERVER_STORES_CIRCLE_PHOTOS,
    private val loadCircles: suspend () -> List<CardCircle> = { emptyList() },
) : ViewModel() {

    private val _state = MutableStateFlow(
        ProfileAvatarEditUiState(circlePhotosSupported = circlePhotosSupported),
    )
    val state: StateFlow<ProfileAvatarEditUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ProfileAvatarEditEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ProfileAvatarEditEvent> = _events.asSharedFlow()

    /** Plaintext cropped bytes per tier, backing that tier's `pendingCroppedAvatar` preview; upload
     *  reads straight from here rather than re-reading the temp file written for the preview. */
    private val pendingCroppedBytes = mutableMapOf<PhotoSlot, ByteArray>()

    /** Which tier an in-flight crop request belongs to, so the result routes back correctly. */
    private val cropRequestSlot = mutableMapOf<Uuid, PhotoSlot>()

    init {
        viewModelScope.launch {
            ownerSession.user.collect { session ->
                _state.update { it.copy(currentAvatar = session) }
            }
        }
        loadExisting()
    }

    private fun loadExisting() {
        viewModelScope.launch {
            val existing = try {
                profileRepository.photoAttributes()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "Failed to load existing profile photos" }
                emptyList()
            }
            val circles = try {
                loadCircles()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "Failed to load card circles" }
                emptyList()
            }
            _state.update {
                it.copy(
                    isLoading = false,
                    photos = existing,
                    circleSlots = circles.associate { c ->
                        c.id to PhotoTierUiState(
                            ProfileVisibility.CONNECTED,
                            circleId = c.id,
                            existing = existing.firstOrNull { a -> a.visibility == ProfileVisibility.CONNECTED && a.isPhotoScopedTo(c.id) },
                        )
                    },
                    anonymous = it.anonymous.copy(
                        existing = existing.firstOrNull { a -> a.visibility == ProfileVisibility.ANONYMOUS },
                    ),
                    onlyMe = it.onlyMe.copy(
                        existing = existing.firstOrNull { a -> a.visibility == ProfileVisibility.OWNER },
                    ),
                    connected = it.connected.copy(
                        existing = existing.firstOrNull { a -> a.visibility == ProfileVisibility.CONNECTED && a.isPhotoScopedTo(null) },
                    ),
                )
            }
        }
    }

    fun onAction(action: ProfileAvatarEditAction) {
        when (action) {
            is ProfileAvatarEditAction.PhotoPicked -> onPhotoPicked(PhotoSlot(action.visibility, action.circleId), action.file)
            is ProfileAvatarEditAction.SaveClicked -> save(PhotoSlot(action.visibility, action.circleId))
            is ProfileAvatarEditAction.RemoveClicked -> stageRemoval(PhotoSlot(action.visibility, action.circleId))
            ProfileAvatarEditAction.BackClicked -> _events.tryEmit(ProfileAvatarEditEvent.Back)
        }
    }

    private fun updateTier(slot: PhotoSlot, block: (PhotoTierUiState) -> PhotoTierUiState) {
        _state.update {
            when {
                slot.circleId != null -> it.copy(circleSlots = it.circleSlots + (slot.circleId to block(it.circleSlot(slot.circleId))))
                slot.visibility == ProfileVisibility.ANONYMOUS -> it.copy(anonymous = block(it.anonymous))
                slot.visibility == ProfileVisibility.OWNER -> it.copy(onlyMe = block(it.onlyMe))
                slot.visibility == ProfileVisibility.CONNECTED -> it.copy(connected = block(it.connected))
                else -> it
            }
        }
    }

    private fun tierState(slot: PhotoSlot): PhotoTierUiState = when {
        slot.circleId != null -> _state.value.circleSlot(slot.circleId)
        slot.visibility == ProfileVisibility.ANONYMOUS -> _state.value.anonymous
        slot.visibility == ProfileVisibility.OWNER -> _state.value.onlyMe
        slot.visibility == ProfileVisibility.CONNECTED -> _state.value.connected
        else -> error("Unsupported profile photo tier: ${slot.visibility}")
    }

    /**
     * Cropping a profile photo is mandatory (the server requires a square image), unlike chat
     * attachments where crop is one of several optional edits before sending — so a picked photo
     * goes straight into the crop pipeline instead of landing on an intermediate preview screen
     * with a crop tool the user would have to notice and choose to use.
     */
    private fun onPhotoPicked(slot: PhotoSlot, file: PlatformFile) {
        viewModelScope.launch {
            try {
                val bytes = file.readBytes()
                val requestId = Uuid.random()
                cropRequestSlot[requestId] = slot
                cropResultBus.postSource(requestId, bytes)
                // Collect on viewModelScope (not tied to this launch) so it survives the
                // crop screen navigating on top of/away from this one — mirrors
                // VaultViewModel.launchEditor.
                viewModelScope.launch {
                    cropResultBus.resultsFor(requestId).collect { result ->
                        val target = cropRequestSlot.remove(requestId) ?: slot
                        applyCroppedResult(target, result.bytes)
                    }
                }
                _events.tryEmit(ProfileAvatarEditEvent.NavigateToCropper(requestId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "Failed to open avatar cropper" }
            }
        }
    }

    private suspend fun applyCroppedResult(slot: PhotoSlot, bytes: ByteArray) {
        pendingCroppedBytes[slot] = bytes
        // Written only so the preview can render via the same PlatformFile/Coil path the rest of
        // the app uses — the upload itself reads pendingCroppedBytes directly.
        val tempPath = fileOperationsProvider.writeBytesToTempFile(bytes, "profile_avatar_", ".jpg")
        updateTier(slot) {
            it.copy(
                // A freshly-picked photo supersedes any staged removal for this tier.
                pendingRemoval = false,
                pendingCroppedAvatar = AttachmentPendingFile.FileImage(
                    id = Uuid.random(),
                    file = platformFileFromPath(tempPath),
                ),
            )
        }
    }

    private fun save(slot: PhotoSlot) {
        val tier = tierState(slot)
        when {
            tier.pendingCroppedAvatar != null -> upload(slot)
            tier.pendingRemoval -> commitRemoval(slot)
        }
    }

    private fun stageRemoval(slot: PhotoSlot) {
        if (tierState(slot).existing == null) return
        updateTier(slot) { it.copy(pendingRemoval = true) }
    }

    private fun upload(slot: PhotoSlot) {
        val bytes = pendingCroppedBytes[slot] ?: return
        if (!tierState(slot).canUpload) return
        if (slot.circleId != null && !circlePhotosSupported) {
            _events.tryEmit(ProfileAvatarEditEvent.UploadFailed(slot.visibility))
            return
        }
        updateTier(slot) { it.copy(isUploading = true) }

        viewModelScope.launch {
            try {
                val fullSize = createImageThumbnail(bytes, "avatar_full", AVATAR_FULL_SIZE_INSTRUCTION)
                val thumbnail = createImageThumbnail(bytes, "avatar_thumb", AVATAR_THUMBNAIL_INSTRUCTION)
                // Blur-up placeholder for instant paint (HomebaseImage's previewThumbnail bridge
                // frame) — reported at the source's natural size, not this tiny rendition's own
                // ~20x20 resized size; see PreviewThumbnail's doc for why.
                val naturalSize = ImageUtils.getNaturalSize(bytes)
                val tinyThumb = createImageThumbnail(bytes, "avatar_tiny", tinyThumbSize, isTinyThumb = true)
                val written = profileRepository.uploadPhoto(
                    contentType = fullSize.contentType,
                    content = fullSize.thumbnailBytes,
                    thumbnails = listOf(thumbnail),
                    visibility = slot.visibility,
                    previewThumbnail = PreviewThumbnail(
                        bytes = tinyThumb.thumbnailBytes,
                        naturalPixelWidth = naturalSize.pixelWidth,
                        naturalPixelHeight = naturalSize.pixelHeight,
                    ),
                    circleId = slot.circleId,
                )

                val refreshed = refreshTier(slot)
                if (slot.circleId != null && refreshed.getOrNull()?.id != written.id) {
                    // Unverified (refresh failed) or stored without its circle scope: either way it may be visible to every connection.
                    try {
                        profileRepository.delete(written.id, written.versionTag)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logger.e(throwable = e, tag = TAG) { "Failed to delete unverified ${slot.circleId} photo ${written.id}" }
                    }
                    error("could not verify the ${slot.circleId} photo is scoped to its circle")
                }
                pendingCroppedBytes.remove(slot)
                // Best-effort refresh so Settings picks up a new Anonymous photo — the public
                // sitedata.json this reads from may lag briefly server-side, and won't reflect a
                // Only-me photo at all (expected — that is not publicly readable).
                // Invalidate the cached image bytes ourselves first — see
                // OwnerSessionRepository.reloadAfterOwnPublish's doc for why the
                // publicProfileContentPublished websocket echo of this same upload arrives too
                // late to prevent Settings from rendering stale bytes at the new cache-bust URL.
                if (slot == PhotoSlot(ProfileVisibility.ANONYMOUS)) {
                    _state.value.currentAvatar?.odinId?.let {
                        ownerSession.reloadAvatar(it)
                    }
                }
                updateTier(slot) { it.copy(isUploading = false, pendingCroppedAvatar = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProfilePhotoTooLargeException) {
                // Distinct from the generic failure below — the server's 400
                // maxContentLengthExceeded is common enough (an unresized/oversized source photo)
                // to warrant its own "choose a smaller one" message rather than a generic failure.
                Logger.w(throwable = e, tag = TAG) { "Profile photo too large for $slot" }
                updateTier(slot) { it.copy(isUploading = false) }
                _events.tryEmit(ProfileAvatarEditEvent.UploadTooLarge(slot.visibility))
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "Profile photo upload failed for $slot" }
                updateTier(slot) { it.copy(isUploading = false) }
                _events.tryEmit(ProfileAvatarEditEvent.UploadFailed(slot.visibility))
            }
        }
    }

    private fun commitRemoval(slot: PhotoSlot) {
        val existing = tierState(slot).existing ?: return
        updateTier(slot) { it.copy(isDeleting = true) }

        viewModelScope.launch {
            val success = try {
                profileRepository.delete(existing.id, existing.versionTag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "Profile photo delete failed for $slot" }
                false
            }

            if (success) {
                updateTier(slot) { it.copy(isDeleting = false, pendingRemoval = false, existing = null) }
                _state.update { it.copy(photos = it.photos.filter { p -> p.id != existing.id }) }
                if (slot == PhotoSlot(ProfileVisibility.ANONYMOUS)) {
                    _state.value.currentAvatar?.odinId?.let {
                        ownerSession.reloadAvatar(it)
                    }
                }
            } else {
                // Leave pendingRemoval set — the tier keeps rendering as removed and Save stays
                // enabled so the user can simply retry, rather than losing the staged removal.
                updateTier(slot) { it.copy(isDeleting = false) }
                _events.tryEmit(ProfileAvatarEditEvent.DeleteFailed(slot.visibility))
            }
        }
    }

    private suspend fun refreshTier(slot: PhotoSlot): Result<ProfileAttribute?> {
        val loaded = try {
            Result.success(profileRepository.photoAttribute(slot.visibility, slot.circleId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(throwable = e, tag = TAG) { "Failed to refresh $slot photo attribute after upload" }
            Result.failure(e)
        }
        val fresh = loaded.getOrNull()
        updateTier(slot) { it.copy(existing = fresh) }
        _state.update { state ->
            val stale = state.photos.filter { it.visibility == slot.visibility && it.isPhotoScopedTo(slot.circleId) }
            state.copy(photos = state.photos - stale.toSet() + listOfNotNull(fresh))
        }
        return loaded
    }
}
