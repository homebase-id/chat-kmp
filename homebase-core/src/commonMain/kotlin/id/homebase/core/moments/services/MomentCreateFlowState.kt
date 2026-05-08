package id.homebase.core.moments.services

import id.homebase.chat.services.builder.AttachmentInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory hand-off between the moments compose screen and the audience
 * picker. The user composes (media + description + comments toggle), the
 * draft lives here while they hop to pick recipients, then the audience
 * screen consumes it on Post and clears.
 *
 * Singleton-scoped so navigating back from audience → compose preserves the
 * draft (the user shouldn't lose their description if they want to change
 * recipients). Cleared on successful post and on explicit cancel.
 */
class MomentCreateFlowState {

    data class Draft(
        val attachments: List<AttachmentInput>,
        val description: String,
        val commentsEnabled: Boolean,
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
