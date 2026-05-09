package id.homebase.core.ui.screens.moments

import id.homebase.api.image.ImageMetadata
import id.homebase.chat.conversationlist.AttachmentPendingFile
import kotlin.time.Instant
import kotlin.uuid.Uuid

data class MomentComposeUiState(
    /**
     * The full-fidelity per-attachment edit model used by the FSAE-style
     * editor (carries `PlatformFile`, video duration/trim, gallery wrappers,
     * etc.). Converted to `AttachmentInput` at the post boundary.
     */
    val attachments: List<AttachmentPendingFile> = emptyList(),
    val description: String = "",
    val currentPage: Int = 0,
    /**
     * The moment's capture timestamp. Auto-derived from the earliest EXIF
     * `DateTimeOriginal` among the photos, then frozen to whatever the user
     * picks if they tap the date chip and override. Null means "no photo had
     * EXIF and the user hasn't picked one" — the sender will fall back to
     * `now()` at post time.
     */
    val momentInstant: Instant? = null,
    /**
     * True when the user explicitly picked a date via the date chip. While
     * this is true, adding/removing photos no longer recomputes [momentInstant]
     * — we don't want a late-arriving photo to silently overwrite the user's
     * pick. Cleared if the user picks the auto-derived value back via the
     * date picker (we treat that as "go back to following the photos").
     */
    val isMomentDateUserOverride: Boolean = false,
) {
    val canContinue: Boolean get() = attachments.isNotEmpty()
}

sealed interface MomentComposeUiAction {
    data class AttachmentsAdded(val attachments: List<AttachmentPendingFile>) : MomentComposeUiAction
    data class AttachmentRemoved(val attachmentId: Uuid) : MomentComposeUiAction
    data class DescriptionChanged(val text: String) : MomentComposeUiAction
    data class PageChanged(val page: Int) : MomentComposeUiAction
    data object NextClicked : MomentComposeUiAction

    /** Image-only: open the cropper for this attachment. */
    data class RequestCrop(val attachmentId: Uuid) : MomentComposeUiAction

    /** Image-only: open the draw editor for this attachment. */
    data class RequestDraw(val attachmentId: Uuid) : MomentComposeUiAction

    /** Replace the bytes for an image after crop returns. */
    data class ApplyCropResult(val attachmentId: Uuid, val croppedBytes: ByteArray) : MomentComposeUiAction

    /** Replace the bytes for an image after draw returns. */
    data class ApplyDrawResult(val attachmentId: Uuid, val paintedBytes: ByteArray) : MomentComposeUiAction

    /** Inline trim scrubber result. Both null clears the trim. */
    data class ApplyTrim(
        val attachmentId: Uuid,
        val trimStartMs: Long?,
        val trimEndMs: Long?,
    ) : MomentComposeUiAction

    /** Save the current attachment to device storage. */
    data class SaveFile(val file: AttachmentPendingFile) : MomentComposeUiAction

    /** Bind decoded duration / poster-frame bytes to a video attachment. */
    data class VideoMetadataResolved(
        val attachmentId: Uuid,
        val durationMs: Long?,
        val thumbnailBytes: ByteArray?,
    ) : MomentComposeUiAction

    /** Bind extracted EXIF metadata to an image attachment. */
    data class ImageMetadataResolved(
        val attachmentId: Uuid,
        val metadata: ImageMetadata?,
    ) : MomentComposeUiAction

    /** Flip the per-image GPS opt-in. No-op if the image has no GPS metadata. */
    data class ToggleIncludeLocation(val attachmentId: Uuid) : MomentComposeUiAction

    /**
     * User picked a date via the date chip. `epochMillis = null` clears the
     * override and resumes auto-derivation from EXIF.
     */
    data class OverrideMomentDate(val epochMillis: Long?) : MomentComposeUiAction
}

sealed interface MomentComposeUiEvent {
    data object NavigateToAudience : MomentComposeUiEvent
    data class NavigateToCropper(val requestId: Uuid) : MomentComposeUiEvent
    data class NavigateToDrawer(val requestId: Uuid) : MomentComposeUiEvent
    data class SaveFileToDevice(val filePath: String, val fileName: String) : MomentComposeUiEvent
    data class ShowError(val message: String) : MomentComposeUiEvent
}
