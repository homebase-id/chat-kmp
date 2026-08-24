package id.homebase.core.ui.screens.moments

import id.homebase.core.moments.services.MomentsRecipient
import id.homebase.core.moments.services.MomentsRecipientId
import id.homebase.core.moments.services.MomentsRecipientsSnapshot
import id.homebase.core.ui.screens.contactbook.CircleMembersUi

data class MomentAudienceUiState(
    val recipients: MomentsRecipientsSnapshot = MomentsRecipientsSnapshot.empty(),
    val selected: Set<MomentsRecipientId> = emptySet(),
    val query: String = "",
    val isPosting: Boolean = false,
    val draftReady: Boolean = false,
    val commentsEnabled: Boolean = true,
    val selfOnly: Boolean = false,
    /** Non-null while the "who's in this circle" roster sheet is open (view-only). */
    val circleDetail: CircleMembersUi? = null,
) {
    val canPost: Boolean
        get() = draftReady && !isPosting && (selfOnly || selected.isNotEmpty())

    /** MRU-bumped recipients matching the search query (or all when query is blank). */
    val filteredRecent: List<MomentsRecipient>
        get() = recipients.recent.matching(query)

    /** Moments groups matching the search query. */
    val filteredGroups: List<MomentsRecipient>
        get() = recipients.groups.matching(query)

    /** User-defined circles matching the search query. */
    val filteredCircles: List<MomentsRecipient>
        get() = recipients.circles.matching(query)

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
    data class CommentsEnabledChanged(val enabled: Boolean) : MomentAudienceUiAction
    data object ToggleSelfOnly : MomentAudienceUiAction
    data object PostClicked : MomentAudienceUiAction

    /** Open the view-only roster for a circle recipient (the "i" affordance on a circle row). */
    data class ShowCircleMembers(val id: MomentsRecipientId) : MomentAudienceUiAction
    data object DismissCircleMembers : MomentAudienceUiAction
}

sealed interface MomentAudienceUiEvent {
    data object Posted : MomentAudienceUiEvent
    data class PostFailed(val message: String?) : MomentAudienceUiEvent
}
