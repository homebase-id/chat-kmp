package id.homebase.chat

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.core.util.ScrollPosition
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlin.time.Instant
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
    val currentOdinId: String = "",
    val fullScreenOverlay: FullScreenOverlay? = null,
    val replyToMessage: MessageUiModel? = null,
    val loadingNewMessage: Boolean = false,

    val uiDialog: ConversationListUiDialog? = null,
    val uiEvent: ConversationListUiEvent? = null,
)

@Immutable
sealed interface FullScreenOverlay {

    data class ViewMessageData(
        val messageId: Uuid,
        val title: String,
        val created: Instant,
        val content: String,
        val fileId: Uuid,
        val driveId: Uuid,
        val payloads: List<PayloadDescriptor>,
        val keyHeader: KeyHeader,
        val selectedPayloadKey: String,
    ) : FullScreenOverlay

    data class AttachmentData(
        val selected: Uuid,
        val conversationTitle: String,
        val conversationId: Uuid,
        val files: List<AttachmentPendingFile>,
    ) : FullScreenOverlay
}

data class AttachmentPendingFile(
    val id: Uuid,
    val file: PlatformFile,
)
