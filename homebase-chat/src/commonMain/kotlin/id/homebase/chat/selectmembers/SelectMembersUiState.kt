package id.homebase.chat.selectmembers

import androidx.compose.runtime.Immutable
import id.homebase.chat.createconversation.ContactGroup
import id.homebase.chat.data.ContactUiModel
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class SelectMembersUiState(
    val uiEvent: SelectMembersUiEvent? = null,
    val displayItems: PersistentList<ContactGroup> = persistentListOf(),
    val selectedContacts: PersistentList<ContactUiModel> = persistentListOf(),
)

sealed interface SelectMembersUiEvent {
    data object Back : SelectMembersUiEvent
    data class MembersSelected(val contactIds: List<String>) : SelectMembersUiEvent
    data class ShowErrorMessage(val message: String) : SelectMembersUiEvent
}
