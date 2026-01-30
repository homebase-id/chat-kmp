package id.homebase.chat

import androidx.compose.runtime.Immutable
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.core.util.ScrollPosition
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlin.uuid.Uuid

@Immutable
data class ConversationListUiState(
    val conversations: ImmutableList<ConversationUiModel> = persistentListOf(),
    val selectedConversationId: Uuid? = null,
    val showingNewChatPane: Boolean = false,
    val contacts: ImmutableList<ContactUiModel> = persistentListOf(),
    val searchQuery: String = "",
    val currentConversationMessages: ImmutableList<MessageUiModel> = persistentListOf(),
    val conversationScrollPosition: ScrollPosition? = null,
    val uiEvent: ConversationListUiEvent? = null,
    val currentOdinId: String = ""
)