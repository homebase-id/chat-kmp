package id.homebase.core.ui.screens.moments

import id.homebase.core.moments.services.MomentsRecipient
import id.homebase.core.moments.services.MomentsRecipientId
import id.homebase.core.moments.services.MomentsRecipientsSnapshot

data class MomentAudienceUiState(
    val recipients: MomentsRecipientsSnapshot = MomentsRecipientsSnapshot.empty(),
    val selected: Set<MomentsRecipientId> = emptySet(),
    val query: String = "",
    val isPosting: Boolean = false,
    val draftReady: Boolean = false,
) {
    val canPost: Boolean get() = draftReady && selected.isNotEmpty() && !isPosting

    /** MRU-bumped recipients matching the search query (or all when query is blank). */
    val filteredRecent: List<MomentsRecipient>
        get() = recipients.recent.matching(query)

    /** Chat conversations (group + 1:1) matching the search query. */
    val filteredConversations: List<MomentsRecipient>
        get() = recipients.conversations.matching(query)

    /** Address-book contacts matching the search query. */
    val filteredContacts: List<MomentsRecipient>
        get() = recipients.contacts.matching(query)

    private fun List<MomentsRecipient>.matching(query: String): List<MomentsRecipient> =
        if (query.isBlank()) this
        else filter { it.displayName.contains(query, ignoreCase = true) }
}

sealed interface MomentAudienceUiAction {
    data class QueryChanged(val text: String) : MomentAudienceUiAction
    data class ToggleRecipient(val id: MomentsRecipientId) : MomentAudienceUiAction
    data object PostClicked : MomentAudienceUiAction
}

sealed interface MomentAudienceUiEvent {
    data object Posted : MomentAudienceUiEvent
    data class PostFailed(val message: String?) : MomentAudienceUiEvent
}
