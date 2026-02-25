package id.homebase.chat.createconversation

import androidx.compose.runtime.Immutable
import id.homebase.chat.data.ContactUiModel
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlin.uuid.Uuid

@Immutable
data class CreateConversationUiState(
    val uiEvent: CreateConversationUiEvent? = null,
    val displayItems: PersistentList<CreateConversationListItem> = persistentListOf(),
)

sealed interface CreateConversationListItem {
    data class Contacts(val contactGroups: List<ContactGroup>) : CreateConversationListItem
    data object NewGroup : CreateConversationListItem
}

data class ContactGroup(
    val initial: String,
    val contacts: List<ContactUiModel>,
)


sealed interface CreateConversationUiEvent {
    data object Back : CreateConversationUiEvent
    data object ShowCreateGroupScreen : CreateConversationUiEvent
    data class LoadConversation(val conversationId: Uuid) : CreateConversationUiEvent
    data class ShowErrorMessage(val message: String) : CreateConversationUiEvent
}
