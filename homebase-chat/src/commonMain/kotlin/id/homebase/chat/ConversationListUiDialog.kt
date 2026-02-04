package id.homebase.chat

import kotlin.uuid.Uuid

sealed interface ConversationListUiDialog {
    data class DeleteMessage(val messageId: Uuid, val allowDeleteForEveryone: Boolean) : ConversationListUiDialog
}