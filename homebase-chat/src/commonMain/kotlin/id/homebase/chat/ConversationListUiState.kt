package id.homebase.chat

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.core.util.ScrollPosition
import kotlin.time.Instant
import kotlin.uuid.Uuid
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import id.homebase.api.common.OdinId

@Immutable
data class ConversationListUiState(
    val conversations: ImmutableList<ConversationUiModel> = persistentListOf(),
    val selectedConversationId: Uuid? = null,
    val showingNewChatPane: Boolean = false,
    val contacts: ImmutableList<ContactUiModel> = persistentListOf(),
    val searchQuery: String = "",
    val currentConversationMessages: ImmutableList<MessageUiModel> = persistentListOf(),
    val conversationScrollPosition: ScrollPosition? = null,
    val currentOdinId: OdinId? = null,
    val fullScreenMedia: FullScreenMessageData? = null,
    val replyToMessage: MessageUiModel? = null,
    val uiDialog: ConversationListUiDialog? = null,
    val uiEvent: ConversationListUiEvent? = null,
)

@Immutable
data class FullScreenMessageData(
    val messageId: Uuid,
    val title: String,
    val created: Instant,
    val content: String,
    val fileId: Uuid,
    val driveId: Uuid,
    val payloads: List<PayloadDescriptor>,
    val keyHeader: KeyHeader,
    val selectedPayloadKey: String,
)
