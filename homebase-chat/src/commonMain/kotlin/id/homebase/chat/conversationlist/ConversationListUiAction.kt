package id.homebase.chat.conversationlist

import id.homebase.api.client.link.LinkPreview
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
    data object NewConversationClicked : ConversationListUiAction
    data object ClearSelection : ConversationListUiAction
    data object FilterByUnreadClicked : ConversationListUiAction
    data object ClearFilterByUnreadClicked : ConversationListUiAction
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

    data class ShowConversationSettings(val conversation: ConversationUiModel) :
        ConversationListUiAction

    data class DeleteConversation(val conversationId: Uuid) : ConversationListUiAction
    data class ArchiveConversation(val conversationId: Uuid) : ConversationListUiAction
    data class ClearConversation(val conversationId: Uuid) : ConversationListUiAction

    data class IntroduceEveryone(val conversationId: Uuid) : ConversationListUiAction
    data class ShowContactInfo(val odinId: String) : ConversationListUiAction
    data class ShowMessageInfo(val message: MessageUiModel) : ConversationListUiAction
    data class ReplyToMessage(val message: MessageUiModel) : ConversationListUiAction
    data object CancelReplyToMessage : ConversationListUiAction
    data class EditMessage(val messageId: Uuid, val versionTag: Uuid, val ignoreDraft: Boolean) :
        ConversationListUiAction

    data object EditMessageSave : ConversationListUiAction
    data object CancelEditMessage : ConversationListUiAction
    data class DeleteMessage(val messageId: Uuid) : ConversationListUiAction
    data class DeleteMessageForMe(val messageId: Uuid) : ConversationListUiAction
    data class DeleteMessageForEveryone(val messageId: Uuid) : ConversationListUiAction

    data class MarkAsRead(val messageId: Uuid) : ConversationListUiAction
    data class ToggleReaction(val conversationId: Uuid, val messageId: Uuid, val reaction: String) :
        ConversationListUiAction

    data class ShowReactionDetails(val messageId: Uuid) : ConversationListUiAction

    data object HideReactionDetails : ConversationListUiAction
}
