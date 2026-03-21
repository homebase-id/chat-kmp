package id.homebase.chat.conversationlist

import androidx.compose.runtime.Immutable
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.chat.services.convo.EnrichedConversationUiModel
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
    val activeConversations: ImmutableList<EnrichedConversationUiModel> = persistentListOf(),
    val conversationsContent: ConversationListContentState = ConversationListContentState.Loading,
    val selectedConversationId: Uuid? = null,
    val filterByUnread: Boolean = false,
    val isSearchActive: Boolean = false,
    val ownerSession: OwnerSession? = null,
    val downloadingFiles: Set<String> = emptySet(),
    val driveIsConnected: Boolean = false,
    val driveIsSyncing: Boolean = false,
    val uiDialog: ConversationListUiDialog? = null,
    val uiEvent: ConversationListUiEvent? = null,
)

sealed interface ConversationListUiSheet {
    data class ConnectIdentities(val identities: List<OdinId>) : ConversationListUiSheet
}

@Immutable
data class MessageListUiState(
    val messages: ImmutableList<MessageListContentModel> = persistentListOf(),
    val scrollPosition: ScrollPosition? = null,
    val fullScreenOverlay: FullScreenOverlay? = null,
    val replyToMessage: MessageUiModel? = null,
    val isEditingMessageId: Uuid? = null,
    val isEditingVersionTag: Uuid? = null,
    val ownerSession: OwnerSession? = null,
    val messageReactions: List<EmojiReaction>? = null,
    val downloadingFiles: Set<String> = emptySet(),
    val recordingData: RecordingData? = null,
    val uiSheet: ConversationListUiSheet? = null,
)

@Immutable
sealed interface ConversationListContentState {
    data object Loading : ConversationListContentState
    data object Empty : ConversationListContentState
    data class EmptySearch(val query: String) : ConversationListContentState
    data class Items(val list: ImmutableList<ConversationListContentModel>) :
        ConversationListContentState
}

@Immutable
sealed interface ConversationListContentModel {
    data class Conversation(val conversation: EnrichedConversationUiModel) : ConversationListContentModel
    data class Message(val message: MessageUiModel) : ConversationListContentModel
    data class Header(val resource: StringResource) : ConversationListContentModel
}

@Immutable
sealed class MessageListContentModel(val id: String) {
    data class Section(val date: LocalDate) : MessageListContentModel(date.toString())
    data class System(val text: String, val created: Instant) : MessageListContentModel(created.toString())
    data class Message(val message: MessageUiModel) :
        MessageListContentModel(message.id.toString() + message.versionTag.toString() + message.hasMore)
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

    data class VideoPlayerData(
        val fileId: Uuid,
        val driveId: Uuid,
        val payloadKey: String,
        val keyHeader: KeyHeader,
        val payload: PayloadDescriptor,
    ) : FullScreenOverlay
}

sealed class AttachmentPendingFile(val attachmentId: Uuid) {
    data class FileImage(val id: Uuid, val file: PlatformFile) : AttachmentPendingFile(id)
    data class File(val id: Uuid, val file: PlatformFile) : AttachmentPendingFile(id)
    data class Gallery(val id: Uuid, val image: GalleryImage) : AttachmentPendingFile(id)
}


@Immutable
data class RecordingData(
    val file: PlatformFile,
    val conversationId: Uuid,
)