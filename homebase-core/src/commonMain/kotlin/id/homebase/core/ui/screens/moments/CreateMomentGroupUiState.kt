package id.homebase.core.ui.screens.moments

import id.homebase.api.common.OdinId
import id.homebase.core.moments.services.MomentsRecipient

data class CreateMomentGroupUiState(
    val title: String = "",
    val query: String = "",
    val contacts: List<MomentsRecipient.Individual> = emptyList(),
    /** Selected by raw OdinId so the set survives lookup re-emissions. */
    val selected: Set<OdinId> = emptySet(),
    val isCreating: Boolean = false,
) {
    val canCreate: Boolean
        get() = title.isNotBlank() && selected.isNotEmpty() && !isCreating

    /**
     * Contacts visible in the picker. Always-include selected so the user
     * doesn't lose sight of who's in the group while typing a search query —
     * a name that doesn't match the query but is already picked still shows.
     * Selected sort to the top so they're easy to review/deselect.
     */
    val filteredContacts: List<MomentsRecipient.Individual>
        get() {
            val matches = if (query.isBlank()) contacts
            else contacts.filter {
                it.odinId in selected || it.displayName.contains(query, ignoreCase = true)
            }
            val (sel, rest) = matches.partition { it.odinId in selected }
            return sel + rest
        }
}

sealed interface CreateMomentGroupUiAction {
    data class TitleChanged(val text: String) : CreateMomentGroupUiAction
    data class QueryChanged(val text: String) : CreateMomentGroupUiAction
    data class ToggleMember(val odinId: OdinId) : CreateMomentGroupUiAction
    data object CreateClicked : CreateMomentGroupUiAction
}

sealed interface CreateMomentGroupUiEvent {
    data object Created : CreateMomentGroupUiEvent
    data class CreateFailed(val message: String?) : CreateMomentGroupUiEvent
}
