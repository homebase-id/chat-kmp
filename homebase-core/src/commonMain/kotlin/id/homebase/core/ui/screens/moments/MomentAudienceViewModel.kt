package id.homebase.core.ui.screens.moments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.touchlab.kermit.Logger
import id.homebase.api.common.time.UnixTimeUtc
import id.homebase.chat.conversationlist.AttachmentPendingFile
import id.homebase.core.moments.services.MomentCreateFlowState
import id.homebase.core.moments.services.MomentSource
import id.homebase.core.moments.services.MomentsPostSenderService
import id.homebase.core.moments.services.MomentsRecipient
import id.homebase.core.moments.services.MomentsRecipientId
import id.homebase.core.moments.services.MomentsRecipientLookupService
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "MomentAudienceViewModel"

class MomentAudienceViewModel(
    private val recipientLookup: MomentsRecipientLookupService,
    private val postSender: MomentsPostSenderService,
    private val flowState: MomentCreateFlowState,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        MomentAudienceUiState(draftReady = flowState.draft.value != null)
    )
    val uiState: StateFlow<MomentAudienceUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MomentAudienceUiEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<MomentAudienceUiEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            recipientLookup.recipients.collect { list ->
                _uiState.update { it.copy(recipients = list) }
            }
        }
        viewModelScope.launch {
            flowState.draft.collect { draft ->
                _uiState.update { it.copy(draftReady = draft != null) }
            }
        }
    }

    fun onAction(action: MomentAudienceUiAction) {
        when (action) {
            is MomentAudienceUiAction.QueryChanged ->
                _uiState.update { it.copy(query = action.text) }

            is MomentAudienceUiAction.ToggleRecipient ->
                _uiState.update {
                    val next = if (action.id in it.selected) it.selected - action.id
                    else it.selected + action.id
                    it.copy(selected = next, selfOnly = false)
                }

            is MomentAudienceUiAction.CommentsEnabledChanged ->
                _uiState.update { it.copy(commentsEnabled = action.enabled) }

            MomentAudienceUiAction.ToggleSelfOnly ->
                _uiState.update {
                    if (it.selfOnly) it.copy(selfOnly = false)
                    else it.copy(selfOnly = true, selected = emptySet())
                }

            MomentAudienceUiAction.PostClicked -> post()
        }
    }

    private fun post() {
        val state = _uiState.value
        val draft = flowState.draft.value
        if (!state.canPost || draft == null) return

        // `recipients` is a recent+others snapshot — flatten via `.all` for the
        // selection lookup, then resolve the picked ids back to recipient objects.
        val selectedRecipients: List<MomentsRecipient> =
            state.recipients.all.filter { it.id in state.selected }
        val odinIds = selectedRecipients.flatMap { it.odinIds }.distinct()

        // Preserve audience provenance: which moments groups were picked, and
        // which standalone individuals. Drop source entirely when nothing
        // structured was selected (only individuals → recipients list IS the
        // source of truth, no need to duplicate). Only set `Audience` when
        // at least one group is in the selection.
        val groupIds = selectedRecipients.filterIsInstance<MomentsRecipient.Group>()
            .map { it.groupId }
        val individualIds = selectedRecipients.filterIsInstance<MomentsRecipient.Individual>()
            .map { it.odinId }
            .distinct()
        val source: MomentSource? = if (groupIds.isNotEmpty()) {
            MomentSource.Audience(groupIds = groupIds, individuals = individualIds)
        } else null

        _uiState.update { it.copy(isPosting = true) }

        viewModelScope.launch {
            try {
                // postMomentAsync only blocks on the synchronous placeholder
                // write + Preparing event emit (~ms); the heavy work (thumbnail
                // generation, encryption, upload, final optimistic update) runs
                // on the service's singleton scope and is tracked by the feed
                // tile via the existing BackendEvent → UploadStatus pipeline.
                postSender.postMomentAsync(
                    // Editor stores AttachmentPendingFile so back-nav can
                    // rehydrate the composer; convert to AttachmentInput at
                    // the post boundary (HEIC/trim/etc. metadata flows through).
                    attachments = draft.attachments.map { it.toAttachmentInput() },
                    description = draft.description,
                    recipients = odinIds,
                    source = source,
                    // Earliest EXIF capture date among the photos, or whatever
                    // the user picked via the date chip. Null falls through to
                    // `now()` in the sender — same behavior as before this field
                    // existed.
                    userDate = draft.momentInstant?.let { UnixTimeUtc.fromInstant(it) },
                    // Aligned-by-index list of optional EXIF info per attachment.
                    // Null when the attachment isn't an image or the user didn't
                    // opt in to anything; the sender drops nulls before keying
                    // by payload key, so an all-null list yields no `mediaInfo`.
                    mediaInfoByAttachment = draft.attachments
                        .map { it.toMediaInfo() }
                        .takeIf { list -> list.any { it != null } },
                    // Poster frame per video attachment (index-aligned with the
                    // attachments above). Lets the sender render the video's
                    // first frame in the placeholder tile and as the optimistic
                    // row's embedded thumbnail instead of a black surface.
                    posterByAttachment = draft.attachments
                        .map { (it as? AttachmentPendingFile.FileVideo)?.thumbnailBytes }
                        .takeIf { list -> list.any { it != null } },
                    commentsEnabled = state.commentsEnabled,
                )
                // recordUsed is fire-and-forget on the lookup service's own
                // singleton scope — see its KDoc. Calling it from
                // viewModelScope used to cancel the in-flight MRU upload the
                // moment we navigated away from this screen.
                recipientLookup.recordUsed(selectedRecipients)
                flowState.clear()
                _events.tryEmit(MomentAudienceUiEvent.Posted)
            } catch (t: Throwable) {
                Logger.e(throwable = t, tag = TAG) { "postMoment failed: ${t.message}" }
                _events.tryEmit(MomentAudienceUiEvent.PostFailed(t.message))
            } finally {
                _uiState.update { it.copy(isPosting = false) }
            }
        }
    }
}
