package id.homebase.chat.conversationlist

import kotlin.uuid.Uuid

sealed interface ConversationListUiDialog {
    data class DeleteMessage(val messageId: Uuid, val allowDeleteForEveryone: Boolean) :
        ConversationListUiDialog

    data class DiscardDraft(val messageId: Uuid, val versionTag: Uuid) : ConversationListUiDialog

    data class DeleteConversation(val conversationId: Uuid) : ConversationListUiDialog
}