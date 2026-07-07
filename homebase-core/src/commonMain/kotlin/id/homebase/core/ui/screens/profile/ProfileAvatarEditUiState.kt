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
    /** The owner's session odinId — after an Anonymous-tier upload/delete, used to refresh the
     *  odinId-keyed public avatar shown elsewhere in the app (e.g. Settings), which reads from a
     *  separate public sitedata.json rather than this screen's own [anonymous] tier state. */
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
    /** Cropped local preview, not yet uploaded. */
    val pendingCroppedAvatar: AttachmentPendingFile.FileImage? = null,
    /** User tapped Remove on [existing] — staged locally, not yet sent to the server. The tier
     *  renders as empty and [existing] is only actually deleted when Save is pressed. */
    val pendingRemoval: Boolean = false,
    val isUploading: Boolean = false,
    val isDeleting: Boolean = false,
) {
    val canUpload: Boolean get() = pendingCroppedAvatar != null && !isUploading
    val canSave: Boolean get() = canUpload || (pendingRemoval && !isDeleting)
}

sealed interface ProfileAvatarEditAction {
    /** Photo was picked — goes straight into the (mandatory, square-locked) crop pipeline. */
    data class PhotoPicked(val visibility: ProfileVisibility, val file: PlatformFile) : ProfileAvatarEditAction
    /** Commits whatever is currently pending for this tier — a cropped upload or a staged removal. */
    data class SaveClicked(val visibility: ProfileVisibility) : ProfileAvatarEditAction
    /** Stages [PhotoTierUiState.existing] for removal; does not call the server. */
    data class RemoveClicked(val visibility: ProfileVisibility) : ProfileAvatarEditAction
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
