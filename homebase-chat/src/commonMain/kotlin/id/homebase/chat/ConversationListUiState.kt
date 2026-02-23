package id.homebase.chat

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.chat.data.IncomingConnectionRequestUiModel
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.core.gallery.GalleryImage
import id.homebase.core.util.ScrollPosition
import id.homebase.core.widget.EmojiReaction
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
    val incomingConnectionRequests: ImmutableList<IncomingConnectionRequestUiModel> = persistentListOf(),
    val searchQuery: String = "",
    val currentConversationMessages: ImmutableList<MessageUiModel> = persistentListOf(),
    val conversationScrollPosition: ScrollPosition? = null,
    val currentOdinId: String = "",
    val fullScreenOverlay: FullScreenOverlay? = null,
    val replyToMessage: MessageUiModel? = null,
    val loadingNewMessage: Boolean = false,

    val messageReactions: List<EmojiReaction>? = null,
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
        val attachments: List<AttachmentPendingFile>,
    ) : FullScreenOverlay
}

sealed class AttachmentPendingFile(val attachmentId: Uuid) {
    data class File(val id: Uuid, val file: PlatformFile) : AttachmentPendingFile(id)
    data class Gallery(val id: Uuid, val image: GalleryImage) : AttachmentPendingFile(id)
}
