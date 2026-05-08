package id.homebase.core.ui.screens.moments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import id.homebase.api.video.VideoThumbnailExtractor
import id.homebase.core.moments.services.MomentCreateFlowState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MomentComposeViewModel(
    private val flowState: MomentCreateFlowState,
) : ViewModel() {

    private val _uiState = MutableStateFlow(restoreFromDraft())
    val uiState: StateFlow<MomentComposeUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MomentComposeUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MomentComposeUiEvent> = _events.asSharedFlow()

    fun onAction(action: MomentComposeUiAction) {
        when (action) {
            is MomentComposeUiAction.AttachmentsAdded -> {
                _uiState.update { it.copy(attachments = it.attachments + action.attachments) }
                // Kick off poster-frame extraction for any videos so the strip
                // can show an actual thumbnail instead of a filename
                // placeholder. Display-only — the upload pipeline generates
                // its own thumbnails downstream. Mirrors chat's
                // ConversationListViewModel.extractThumbnailAsync pattern.
                action.attachments.forEach { attachment ->
                    if (attachment.contentType.startsWith("video/")) {
                        extractVideoThumbnail(attachment.filePath)
                    }
                }
            }

            is MomentComposeUiAction.AttachmentRemoved ->
                _uiState.update {
                    it.copy(
                        attachments = it.attachments.filterNot { a -> a.filePath == action.filePath },
                        videoThumbnails = it.videoThumbnails - action.filePath,
                    )
                }

            is MomentComposeUiAction.DescriptionChanged ->
                _uiState.update { it.copy(description = action.text) }

            is MomentComposeUiAction.CommentsEnabledChanged ->
                _uiState.update { it.copy(commentsEnabled = action.enabled) }

            MomentComposeUiAction.NextClicked -> {
                val s = _uiState.value
                if (!s.canContinue) return
                flowState.setDraft(
                    MomentCreateFlowState.Draft(
                        attachments = s.attachments,
                        description = s.description,
                        commentsEnabled = s.commentsEnabled,
                    )
                )
                _events.tryEmit(MomentComposeUiEvent.NavigateToAudience)
            }
        }
    }

    private fun extractVideoThumbnail(filePath: String) {
        viewModelScope.launch {
            val bytes = runCatching {
                VideoThumbnailExtractor.extractPosterFrame(filePath)
            }.getOrNull() ?: return@launch
            _uiState.update { state ->
                // The user might have removed the attachment while extraction
                // was in flight — guard so we don't reattach a zombie thumbnail.
                if (state.attachments.none { it.filePath == filePath }) state
                else state.copy(videoThumbnails = state.videoThumbnails + (filePath to bytes))
            }
        }
    }

    private fun restoreFromDraft(): MomentComposeUiState {
        // Returning from audience picker → preserve compose state so the user
        // doesn't lose their description / media if they want to amend recipients.
        val draft = flowState.draft.value ?: return MomentComposeUiState()
        return MomentComposeUiState(
            attachments = draft.attachments,
            description = draft.description,
            commentsEnabled = draft.commentsEnabled,
        )
    }
}
