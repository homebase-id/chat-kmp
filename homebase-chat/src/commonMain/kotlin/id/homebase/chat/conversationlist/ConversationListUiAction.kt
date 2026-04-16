package id.homebase.chat.conversationlist

import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.files.PayloadDescriptor
import id.homebase.api.client.link.LinkPreview
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ConversationUiModel
import id.homebase.chat.data.MessageUiModel
import id.homebase.core.gallery.GalleryImage
import io.github.vinceglb.filekit.PlatformFile
import kotlin.uuid.Uuid

sealed interface ConversationListUiAction {
    data class ConversationClicked(val conversationId: Uuid, val messageId: Uuid?) :
        ConversationListUiAction

    data object BackClicked : ConversationListUiAction
    data object SearchClicked : ConversationListUiAction
    data object SearchBackClicked : ConversationListUiAction
    data object SearchMessagesClicked : ConversationListUiAction
    data object SearchMessagesBackClicked : ConversationListUiAction
    data object SearchMessagesNavigateNext : ConversationListUiAction
    data object SearchMessagesNavigatePrevious : ConversationListUiAction
    data object NewConversationClicked : ConversationListUiAction
    data object ClearSelection : ConversationListUiAction
    data object FilterByUnreadClicked : ConversationListUiAction
    data object ClearFilterByUnreadClicked : ConversationListUiAction
    data object ShowArchivedMessagesClicked : ConversationListUiAction
    data class ConnectIdentities(val identities: List<OdinId>) : ConversationListUiAction
    data class ConnectToIdentity(val odinId: OdinId) : ConversationListUiAction
    data class OpenConnectionRequestInOwnerConsole(val odinId: OdinId) : ConversationListUiAction
    data class OpenSendConnectionRequestDialog(val odinId: OdinId) : ConversationListUiAction
    data object DismissSheet : ConversationListUiAction
    data class SendMessage(val conversationId: Uuid, val linkPreview: LinkPreview? = null) :
        ConversationListUiAction

    data class SendFile(
        val conversationId: Uuid,
        val message: String,
        val attachments: List<AttachmentPendingFile>
    ) : ConversationListUiAction

    data class AttachGalleryItem(
        val conversationId: Uuid,
        val files: List<GalleryImage>,
    ) : ConversationListUiAction

    data class AttachPlatformFile(
        val conversationId: Uuid,
        val files: List<PlatformFile>,
        val isImage: Boolean = false,
    ) : ConversationListUiAction

    data class UnAttachFile(
        val conversationId: Uuid,
        val id: Uuid,
    ) : ConversationListUiAction

    data class ShareMedia(val messageId: Uuid, val payloadKey: String) : ConversationListUiAction

    data class ShareMessage(val message: MessageUiModel) : ConversationListUiAction
    data class DownloadMedia(val messageId: Uuid, val payloadKey: String) : ConversationListUiAction
    data class DownloadVideoMedia(
        val fileId: Uuid,
        val payloadKey: String,
        val keyHeader: KeyHeader,
        val payload: PayloadDescriptor,
    ) : ConversationListUiAction

    data class SaveFile(val file: AttachmentPendingFile) : ConversationListUiAction

    data class MediaClicked(val message: MessageUiModel, val payloadKey: String) :
        ConversationListUiAction

    data class ShowMoreClicked(val conversationId: Uuid, val messageId: Uuid) :
        ConversationListUiAction

    data object CloseFullScreenOverlay : ConversationListUiAction

    data class SaveScrollPosition(
        val conversationId: Uuid,
        val firstVisibleItemIndex: Int,
        val firstVisibleItemScrollOffset: Int
    ) : ConversationListUiAction

    data object ClearScrollTrigger : ConversationListUiAction

    data class ShowConversationSettings(val conversation: ConversationUiModel) :
        ConversationListUiAction

    data class DeleteConversation(val conversationId: Uuid) : ConversationListUiAction
    data class ConfirmDeleteConversation(val conversationId: Uuid) : ConversationListUiAction
    data class ArchiveConversation(val conversationId: Uuid) : ConversationListUiAction

    data class UnarchiveConversation(val conversationId: Uuid) : ConversationListUiAction


    data class ClearConversation(val conversationId: Uuid) : ConversationListUiAction

    data class IntroduceEveryone(val conversationId: Uuid) : ConversationListUiAction
    data class ShowContactInfo(val odinId: String) : ConversationListUiAction
    data class ShowMessageInfo(val message: MessageUiModel) : ConversationListUiAction
    data class ReplyToMessage(val message: MessageUiModel) : ConversationListUiAction
    data class ForwardMessage(val message: MessageUiModel) : ConversationListUiAction
    data class ForwardMessageSend(val message: MessageUiModel, val recipients: List<RecipientModel>) : ConversationListUiAction
    data class ForwardMessageSelectRecipient(val recipient: RecipientModel) : ConversationListUiAction
    data object CancelReplyToMessage : ConversationListUiAction
    data class EditMessage(val messageId: Uuid, val versionTag: Uuid, val ignoreDraft: Boolean) :
        ConversationListUiAction

    data object EditMessageSave : ConversationListUiAction
    data object CancelEditMessage : ConversationListUiAction
    data class DeleteMessage(val messageId: Uuid) : ConversationListUiAction
    data class DeleteMessageForMe(val messageId: Uuid) : ConversationListUiAction
    data class DeleteMessageForEveryone(val messageId: Uuid) : ConversationListUiAction

    data class TogglePinConversation(val conversationId: Uuid) : ConversationListUiAction
    data class AcceptRejoin(val conversationId: Uuid) : ConversationListUiAction
    data class DeclineRejoin(val conversationId: Uuid) : ConversationListUiAction
    data class MarkAsRead(val conversationId: Uuid, val messageIds: List<Uuid>? = null) : ConversationListUiAction
    data class ToggleReaction(val conversationId: Uuid, val messageId: Uuid, val reaction: String) :
        ConversationListUiAction

    data class ShowReactionDetails(val messageId: Uuid) : ConversationListUiAction
    data class DecryptFile(val messageId: Uuid, val payloadKey: String) : ConversationListUiAction
    data class ScrollToMessageId(val messageId: Uuid) : ConversationListUiAction
    data object HideReactionDetails : ConversationListUiAction
    data class StartRecording(val conversationId: Uuid) : ConversationListUiAction
    data object StopRecording : ConversationListUiAction
    data object CancelRecording : ConversationListUiAction
    data object ShowRecordingHelp : ConversationListUiAction

    data class AttachClipboardImage(
        val conversationId: Uuid,
        val imageBytes: ByteArray,
    ) : ConversationListUiAction

    data class BlockUser(val authorOdinId: OdinId) : ConversationListUiAction
    data object ReportContent : ConversationListUiAction
}
