package id.homebase.chat

import id.homebase.chat.data.ContactUiModel
import kotlin.uuid.Uuid

sealed interface ConversationListUiAction {
    data class ConversationClicked(val conversationId: Uuid) : ConversationListUiAction
    data object BackClicked : ConversationListUiAction
    data object NewChatClicked : ConversationListUiAction
    data object BackToListClicked : ConversationListUiAction
    data class ContactClicked(val contact: ContactUiModel) : ConversationListUiAction
    data class SearchQueryChanged(val query: String) : ConversationListUiAction
    data class SendMessage(val conversationId: Uuid, val content: String) : ConversationListUiAction

    data class SaveScrollPosition(
        val conversationId: Uuid,
        val firstVisibleItemIndex: Int,
        val firstVisibleItemScrollOffset: Int
    ) : ConversationListUiAction

    data class ShowConversationInfo(val conversationId: Uuid) : ConversationListUiAction
    data class DeleteConversation(val conversationId: Uuid) : ConversationListUiAction
    data class ArchiveConversation(val conversationId: Uuid) : ConversationListUiAction
    data class ClearConversation(val conversationId: Uuid) : ConversationListUiAction

    data class ShowMessageInfo(val messageId: Uuid) : ConversationListUiAction
    data class ReplyToMessage(val messageId: Uuid) : ConversationListUiAction
    data class StarMessage(val messageId: Uuid) : ConversationListUiAction
    data class EditMessage(val messageId: Uuid) : ConversationListUiAction
    data class DeleteMessageForMe(val messageId: Uuid) : ConversationListUiAction
    data class DeleteMessageForEveryone(val messageId: Uuid) : ConversationListUiAction

    data class MarkAsRead(val messageId: Uuid) : ConversationListUiAction
    data class AddReaction(val messageId: Uuid, val reaction: String) : ConversationListUiAction
    data class DeleteReaction(val messageId: Uuid, val reaction: String) : ConversationListUiAction

    // Attachments (INTENTS)
    data object PickImage : ConversationListUiAction
    data object PickVideo : ConversationListUiAction
    data object PickFile : ConversationListUiAction

    // Attachments (RESULTS)
    data class ImagePicked(val filePath: String, val name: String) : ConversationListUiAction
    data class VideoPicked(val filePath: String, val name: String) : ConversationListUiAction
    data class FilePicked(val filePath: String, val name: String) : ConversationListUiAction

    data object AttachmentPickCancelled : ConversationListUiAction
    data class AttachmentPickFailed(val reason: String) : ConversationListUiAction
}
