package id.homebase.chat.conversationlist

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.core.gallery.GalleryImage
import id.homebase.core.util.ScrollPosition
import id.homebase.core.widget.EmojiReaction
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Instant
import kotlin.uuid.Uuid

@Immutable
data class ConversationListUiState(
    val activeConversations: ImmutableList<ConversationUiModel> = persistentListOf(),
    val conversationsContent: ConversationListContentState = ConversationListContentState.Empty,
    val selectedConversationId: Uuid? = null,
    val currentConversationMessages: ImmutableList<MessageListContentModel> = persistentListOf(),
    val conversationScrollPosition: ScrollPosition? = null,
    val currentOdinId: String = "",
    val fullScreenOverlay: FullScreenOverlay? = null,
    val replyToMessage: MessageUiModel? = null,
    val loadingNewMessage: Boolean = false,
    val filterByUnread: Boolean = false,
    val isSearchActive: Boolean = false,
    val ownerSession: OwnerSession? = null,
    val messageReactions: List<EmojiReaction>? = null,
    val uiDialog: ConversationListUiDialog? = null,
    val uiEvent: ConversationListUiEvent? = null,
)

@Immutable
sealed interface ConversationListContentState {
    data object Empty : ConversationListContentState
    data class EmptySearch(val query: String) : ConversationListContentState
    data class Items(val list: ImmutableList<ConversationListContentModel>) : ConversationListContentState
}

@Immutable
sealed interface ConversationListContentModel {
    data class Conversation(val conversation: ConversationUiModel) : ConversationListContentModel
    data class Message(val message: MessageUiModel) : ConversationListContentModel
    data class Header(val resource: StringResource) : ConversationListContentModel
}

@Immutable
sealed class MessageListContentModel(val id: String) {
    data class Section(val date: LocalDate) : MessageListContentModel(date.toString())
    data class Message(val message: MessageUiModel) : MessageListContentModel(message.id.toString())
}

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
        val attachments: List<AttachmentPendingFile>,
    ) : FullScreenOverlay
}

sealed class AttachmentPendingFile(val attachmentId: Uuid) {
    data class File(val id: Uuid, val file: PlatformFile) : AttachmentPendingFile(id)
    data class Gallery(val id: Uuid, val image: GalleryImage) : AttachmentPendingFile(id)
}
