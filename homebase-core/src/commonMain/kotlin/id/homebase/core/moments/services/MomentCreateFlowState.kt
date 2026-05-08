package id.homebase.core.moments.services

import id.homebase.chat.conversationlist.AttachmentPendingFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory hand-off between the moments compose screen and the audience
 * picker. The user composes (media + description), the draft lives here while
 * they hop to pick recipients, then the audience screen consumes it on Post
 * and clears.
 *
 * The draft holds the editor's full-fidelity per-attachment model
 * ([AttachmentPendingFile]) so navigating back from the audience picker can
 * rehydrate the composer with the same attachments the user picked, including
 * any video trim ranges and crop/draw edits. The audience VM converts to
 * `AttachmentInput` at post time.
 *
 * The comments-enabled toggle lives on the audience screen's UI state, not
 * here.
 *
 * Singleton-scoped so navigating back from audience → compose preserves the
 * draft. Cleared on successful post and on explicit cancel.
 */
class MomentCreateFlowState {

    data class Draft(
        val attachments: List<AttachmentPendingFile>,
        val description: String,
    )

    private val _draft = MutableStateFlow<Draft?>(null)
    val draft: StateFlow<Draft?> = _draft.asStateFlow()

    fun setDraft(draft: Draft) {
        _draft.value = draft
    }

    fun clear() {
        _draft.value = null
    }
}
