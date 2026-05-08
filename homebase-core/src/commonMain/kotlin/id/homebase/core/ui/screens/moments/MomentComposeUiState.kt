package id.homebase.core.ui.screens.moments

import id.homebase.chat.services.builder.AttachmentInput

data class MomentComposeUiState(
    val attachments: List<AttachmentInput> = emptyList(),
    /**
     * Display-only poster frames for video attachments, keyed by [AttachmentInput.filePath].
     * Populated asynchronously after a video is added via [VideoThumbnailExtractor]. The
     * post-sender pipeline ([MessageAttachmentBuilder]) generates its own thumbnails for the
     * encrypted upload — these bytes are never sent.
     */
    val videoThumbnails: Map<String, ByteArray> = emptyMap(),
    val description: String = "",
    val commentsEnabled: Boolean = false,
) {
    val canContinue: Boolean get() = attachments.isNotEmpty()
}

sealed interface MomentComposeUiAction {
    data class AttachmentsAdded(val attachments: List<AttachmentInput>) : MomentComposeUiAction
    data class AttachmentRemoved(val filePath: String) : MomentComposeUiAction
    data class DescriptionChanged(val text: String) : MomentComposeUiAction
    data class CommentsEnabledChanged(val enabled: Boolean) : MomentComposeUiAction
    data object NextClicked : MomentComposeUiAction
}

sealed interface MomentComposeUiEvent {
    data object NavigateToAudience : MomentComposeUiEvent
}
