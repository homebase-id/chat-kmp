package id.homebase.core.ui.screens.moments

import id.homebase.api.image.ImageMetadata
import id.homebase.chat.conversationlist.AttachmentPendingFile
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
}

sealed interface MomentComposeUiEvent {
    data object NavigateToAudience : MomentComposeUiEvent
    data class NavigateToCropper(val requestId: Uuid) : MomentComposeUiEvent
    data class NavigateToDrawer(val requestId: Uuid) : MomentComposeUiEvent
    data class SaveFileToDevice(val filePath: String, val fileName: String) : MomentComposeUiEvent
    data class ShowError(val message: String) : MomentComposeUiEvent
}
