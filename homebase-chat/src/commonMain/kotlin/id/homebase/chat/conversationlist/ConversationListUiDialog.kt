package id.homebase.chat.conversationlist

import id.homebase.api.client.connections.IntroductionPreflightResult
import kotlin.uuid.Uuid

sealed interface ConversationListUiDialog {
    data class DeleteMessage(val messageId: Uuid, val allowDeleteForEveryone: Boolean) :
        ConversationListUiDialog

    data class DiscardDraft(val messageId: Uuid, val versionTag: Uuid) : ConversationListUiDialog

    data class DeleteConversation(val conversationId: Uuid) : ConversationListUiDialog

    /** Surfaced when a preflight check before [IntroductionSender.sendIntroductions]
     *  reports any recipient as non-Ready. The user picks one of three outcomes:
     *  send anyway with the original list, send only to the Ready subset, or cancel.
     *  See ConversationListViewModel.introduceEveryone() for the call site. */
    data class IntroducePreflight(
        val conversationId: Uuid,
        val message: String,
        val result: IntroductionPreflightResult,
    ) : ConversationListUiDialog
}