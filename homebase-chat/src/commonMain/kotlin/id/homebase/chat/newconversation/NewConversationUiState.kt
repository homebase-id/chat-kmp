package id.homebase.chat.newconversation

import androidx.compose.runtime.Immutable
import id.homebase.chat.data.ContactUiModel
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlin.uuid.Uuid

@Immutable
data class NewConversationUiState(
    val uiEvent: NewConversationUiEvent? = null,
    val contacts : PersistentList<ContactUiModel> = persistentListOf(),
    val items: PersistentList<NewConversationListItem> = persistentListOf(),
)

sealed interface NewConversationListItem {
    data class Contacts(val contactGroups: List<ContactGroup>) : NewConversationListItem
    data object NewGroup : NewConversationListItem
}

data class ContactGroup(
    val initial: String,
    val contacts: List<ContactUiModel>,
)


sealed interface NewConversationUiEvent {
    data object Back : NewConversationUiEvent
    data object ShowCreateGroupScreen : NewConversationUiEvent
    data class LoadConversation(val conversationId: Uuid) : NewConversationUiEvent
    data class ShowErrorMessage(val message: String) : NewConversationUiEvent
}
