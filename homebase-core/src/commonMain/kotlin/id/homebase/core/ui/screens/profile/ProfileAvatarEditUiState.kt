@file:OptIn(ExperimentalUuidApi::class)

package id.homebase.core.ui.screens.profile

import androidx.compose.runtime.Immutable
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.chat.conversationlist.AttachmentPendingFile
import io.github.vinceglb.filekit.PlatformFile
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Dedicated screen for managing the owner's profile photo — separate from the standard-profile
 * text-attribute editor ([ProfileEditUiState]). Manages two independent photo slots, one per
 * [ProfileVisibility] tier: [anonymous] (the public avatar) and [connected] (shown only to
 * connections) — the backend supports every tier, but this screen only exposes the two the
 * product wants surfaced today.
 */
@Immutable
data class ProfileAvatarEditUiState(
    /** The owner's session — used for odinId/initials on the [anonymous] tier's public-avatar preview. */
    val currentAvatar: OwnerSession? = null,
    val isLoading: Boolean = true,
    val anonymous: PhotoTierUiState = PhotoTierUiState(ProfileVisibility.ANONYMOUS),
    val connected: PhotoTierUiState = PhotoTierUiState(ProfileVisibility.CONNECTED),
)

/** One [ProfileVisibility] tier's photo slot: what's currently stored, and any in-flight edit. */
@Immutable
data class PhotoTierUiState(
    val visibility: ProfileVisibility,
    /** The currently-uploaded attribute for this tier, if any — null means "not set". */
    val existing: ProfileAttribute? = null,
    /** Just-picked, not-yet-cropped photo. Non-null drives the [id.homebase.chat.widget.MediaAttachmentEditor] overlay. */
    val pendingSourceAttachment: AttachmentPendingFile.FileImage? = null,
    /** Cropped local preview, not yet uploaded. */
    val pendingCroppedAvatar: AttachmentPendingFile.FileImage? = null,
    val isUploading: Boolean = false,
    val isDeleting: Boolean = false,
) {
    val canUpload: Boolean get() = pendingCroppedAvatar != null && !isUploading
}

sealed interface ProfileAvatarEditAction {
    data class PhotoPicked(val visibility: ProfileVisibility, val file: PlatformFile) : ProfileAvatarEditAction
    data class CropRequested(val visibility: ProfileVisibility, val attachmentId: Uuid) : ProfileAvatarEditAction
    data class PhotoEditorDismissed(val visibility: ProfileVisibility) : ProfileAvatarEditAction
    data class UploadClicked(val visibility: ProfileVisibility) : ProfileAvatarEditAction
    data class DeleteClicked(val visibility: ProfileVisibility) : ProfileAvatarEditAction
    data object BackClicked : ProfileAvatarEditAction
}

sealed interface ProfileAvatarEditEvent {
    data class NavigateToCropper(val requestId: Uuid) : ProfileAvatarEditEvent
    /** The user backed out with no unsaved changes — pop back. */
    data object Back : ProfileAvatarEditEvent
    data class UploadFailed(val visibility: ProfileVisibility) : ProfileAvatarEditEvent
    /** The server rejected the photo as too large (400 maxContentLengthExceeded) — distinct from
     *  [UploadFailed] so the UI can prompt for a smaller photo instead of a generic error. */
    data class UploadTooLarge(val visibility: ProfileVisibility) : ProfileAvatarEditEvent
    data class DeleteFailed(val visibility: ProfileVisibility) : ProfileAvatarEditEvent
}
