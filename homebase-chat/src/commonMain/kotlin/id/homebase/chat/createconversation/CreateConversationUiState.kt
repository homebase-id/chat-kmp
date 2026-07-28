package id.homebase.chat.createconversation

import androidx.compose.runtime.Immutable
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.core.avatars.ConversationAvatarModel
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlin.uuid.Uuid

@Immutable
data class CreateConversationUiState(
    val uiEvent: CreateConversationUiEvent? = null,
    val isRefreshing: Boolean = false,
    val displayItems: PersistentList<CreateConversationListItem> = persistentListOf(),
)

sealed interface CreateConversationListItem {
    data class Contacts(val contactGroups: List<ContactGroup>) : CreateConversationListItem
    data object NewGroup : CreateConversationListItem

    /** Existing group conversations, selectable to open the group directly. */
    data class Groups(val groups: List<GroupRow>) : CreateConversationListItem

    @Immutable
    data class GroupRow(
        val id: Uuid,
        val avatarModel: ConversationAvatarModel,
        val name: String,
    )

    /**
     * [self] is non-null only when surfaced by a self-name search → render as a contact row with the
     * user's own avatar + "Name (you)". Null = the idle "Note to Self" action (sticky-note icon).
     */
    data class NoteToSelf(val self: SelfRef? = null) : CreateConversationListItem

    /** The signed-in user, as needed to render their search-result row (avatar + name). */
    @Immutable
    data class SelfRef(val odinId: OdinId, val name: String, val initials: String)
    data object NewContact : CreateConversationListItem
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
