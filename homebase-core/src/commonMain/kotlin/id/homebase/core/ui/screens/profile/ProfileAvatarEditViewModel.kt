@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSessionRepository
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfilePhotoTooLargeException
import id.homebase.api.client.profile.ProfileRepository
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.file.FileOperationsProvider
import id.homebase.api.image.ImageFormat
import id.homebase.api.image.ThumbnailInstruction
import id.homebase.api.image.createImageThumbnail
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.core.clipboard.platformFileFromPath
import id.homebase.imageeditor.ui.CropResultBus
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val TAG = "ProfileAvatarEditViewModel"

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
 * Drives the dedicated avatar-edit screen: two independent photo slots (Anonymous, Connected).
 * Each slot's flow is pick → crop (via the same [id.homebase.imageeditor.ui.CropScreen] chat
 * attachments use, locked to a square aspect) → upload via
 * `PUT /api/v2/profile/attributes/photo` ([ProfileRepository.uploadPhoto]) → delete.
 */
class ProfileAvatarEditViewModel(
    private val ownerSessionRepository: OwnerSessionRepository,
    private val profileRepository: ProfileRepository,
    private val cropResultBus: CropResultBus,
    private val fileOperationsProvider: FileOperationsProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileAvatarEditUiState())
    val state: StateFlow<ProfileAvatarEditUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ProfileAvatarEditEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ProfileAvatarEditEvent> = _events.asSharedFlow()

    /** Plaintext cropped bytes per tier, backing that tier's `pendingCroppedAvatar` preview; upload
     *  reads straight from here rather than re-reading the temp file written for the preview. */
    private val pendingCroppedBytes = mutableMapOf<ProfileVisibility, ByteArray>()

    /** Which tier an in-flight crop request belongs to, so the result routes back correctly. */
    private val cropRequestVisibility = mutableMapOf<Uuid, ProfileVisibility>()

    init {
        viewModelScope.launch {
            ownerSessionRepository.user.collect { session ->
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
            _state.update {
                it.copy(
                    isLoading = false,
                    anonymous = it.anonymous.copy(
                        existing = existing.firstOrNull { a -> a.visibility == ProfileVisibility.ANONYMOUS },
                    ),
                    connected = it.connected.copy(
                        existing = existing.firstOrNull { a -> a.visibility == ProfileVisibility.CONNECTED },
                    ),
                )
            }
        }
    }

    fun onAction(action: ProfileAvatarEditAction) {
        when (action) {
            is ProfileAvatarEditAction.PhotoPicked -> onPhotoPicked(action.visibility, action.file)
            is ProfileAvatarEditAction.CropRequested -> onCropRequested(action.visibility, action.attachmentId)
            is ProfileAvatarEditAction.PhotoEditorDismissed ->
                updateTier(action.visibility) { it.copy(pendingSourceAttachment = null) }
            is ProfileAvatarEditAction.UploadClicked -> upload(action.visibility)
            is ProfileAvatarEditAction.DeleteClicked -> delete(action.visibility)
            ProfileAvatarEditAction.BackClicked -> _events.tryEmit(ProfileAvatarEditEvent.Back)
        }
    }

    private fun updateTier(visibility: ProfileVisibility, block: (PhotoTierUiState) -> PhotoTierUiState) {
        _state.update {
            when (visibility) {
                ProfileVisibility.ANONYMOUS -> it.copy(anonymous = block(it.anonymous))
                ProfileVisibility.CONNECTED -> it.copy(connected = block(it.connected))
                else -> it
            }
        }
    }

    private fun tierState(visibility: ProfileVisibility): PhotoTierUiState = when (visibility) {
        ProfileVisibility.ANONYMOUS -> _state.value.anonymous
        ProfileVisibility.CONNECTED -> _state.value.connected
        else -> error("Unsupported profile photo tier: $visibility")
    }

    private fun onPhotoPicked(visibility: ProfileVisibility, file: PlatformFile) {
        updateTier(visibility) {
            it.copy(pendingSourceAttachment = AttachmentPendingFile.FileImage(id = Uuid.random(), file = file))
        }
    }

    private fun onCropRequested(visibility: ProfileVisibility, attachmentId: Uuid) {
        val source = tierState(visibility).pendingSourceAttachment
            ?.takeIf { it.attachmentId == attachmentId } ?: return
        viewModelScope.launch {
            try {
                val bytes = source.file.readBytes()
                val requestId = Uuid.random()
                cropRequestVisibility[requestId] = visibility
                cropResultBus.postSource(requestId, bytes)
                // Collect on viewModelScope (not tied to this launch) so it survives the
                // crop screen navigating on top of/away from this one — mirrors
                // VaultViewModel.launchEditor.
                viewModelScope.launch {
                    cropResultBus.resultsFor(requestId).collect { result ->
                        val target = cropRequestVisibility.remove(requestId) ?: visibility
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

    private suspend fun applyCroppedResult(visibility: ProfileVisibility, bytes: ByteArray) {
        pendingCroppedBytes[visibility] = bytes
        // Written only so the preview can render via the same PlatformFile/Coil path the rest of
        // the app uses — the upload itself reads pendingCroppedBytes directly.
        val tempPath = fileOperationsProvider.writeBytesToTempFile(bytes, "profile_avatar_", ".jpg")
        updateTier(visibility) {
            it.copy(
                pendingSourceAttachment = null,
                pendingCroppedAvatar = AttachmentPendingFile.FileImage(
                    id = Uuid.random(),
                    file = platformFileFromPath(tempPath),
                ),
            )
        }
    }

    private fun upload(visibility: ProfileVisibility) {
        val bytes = pendingCroppedBytes[visibility] ?: return
        if (!tierState(visibility).canUpload) return
        updateTier(visibility) { it.copy(isUploading = true) }

        viewModelScope.launch {
            try {
                val fullSize = createImageThumbnail(bytes, "avatar_full", AVATAR_FULL_SIZE_INSTRUCTION)
                val thumbnail = createImageThumbnail(bytes, "avatar_thumb", AVATAR_THUMBNAIL_INSTRUCTION)
                profileRepository.uploadPhoto(
                    contentType = fullSize.contentType,
                    content = fullSize.thumbnailBytes,
                    thumbnails = listOf(thumbnail),
                    visibility = visibility,
                )

                pendingCroppedBytes.remove(visibility)
                refreshTier(visibility)
                // Best-effort refresh so Settings picks up a new Anonymous photo — the public
                // sitedata.json this reads from may lag briefly server-side, and won't reflect a
                // CONNECTED-visibility photo at all (expected — that's not publicly readable).
                if (visibility == ProfileVisibility.ANONYMOUS) {
                    _state.value.currentAvatar?.odinId?.let { ownerSessionRepository.load(it) }
                }
                updateTier(visibility) { it.copy(isUploading = false, pendingCroppedAvatar = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProfilePhotoTooLargeException) {
                // Distinct from the generic failure below — the server's 400
                // maxContentLengthExceeded is common enough (an unresized/oversized source photo)
                // to warrant its own "choose a smaller one" message rather than a generic failure.
                Logger.w(throwable = e, tag = TAG) { "Profile photo too large for $visibility" }
                updateTier(visibility) { it.copy(isUploading = false) }
                _events.tryEmit(ProfileAvatarEditEvent.UploadTooLarge(visibility))
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "Profile photo upload failed for $visibility" }
                updateTier(visibility) { it.copy(isUploading = false) }
                _events.tryEmit(ProfileAvatarEditEvent.UploadFailed(visibility))
            }
        }
    }

    private fun delete(visibility: ProfileVisibility) {
        val existing = tierState(visibility).existing ?: return
        updateTier(visibility) { it.copy(isDeleting = true) }

        viewModelScope.launch {
            val success = try {
                profileRepository.delete(existing.id, existing.versionTag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(throwable = e, tag = TAG) { "Profile photo delete failed for $visibility" }
                false
            }

            if (success) {
                updateTier(visibility) { it.copy(isDeleting = false, existing = null) }
                if (visibility == ProfileVisibility.ANONYMOUS) {
                    _state.value.currentAvatar?.odinId?.let { ownerSessionRepository.load(it) }
                }
            } else {
                updateTier(visibility) { it.copy(isDeleting = false) }
                _events.tryEmit(ProfileAvatarEditEvent.DeleteFailed(visibility))
            }
        }
    }

    private suspend fun refreshTier(visibility: ProfileVisibility) {
        val fresh: ProfileAttribute? = try {
            profileRepository.photoAttribute(visibility)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(throwable = e, tag = TAG) { "Failed to refresh $visibility photo attribute after upload" }
            null
        }
        updateTier(visibility) { it.copy(existing = fresh) }
    }
}
