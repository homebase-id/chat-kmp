package id.homebase.chat.conversationlist

import id.homebase.chat.data.MessageUiModel
import org.jetbrains.compose.resources.StringResource

sealed interface ConversationListUiEvent {
    data object NavigateBack : ConversationListUiEvent
    data object NavigateToNewConversation : ConversationListUiEvent
    data class NavigateToContactInfo(val odinId: String) : ConversationListUiEvent
    data class NavigateToGroupSettings(val conversationId: String) : ConversationListUiEvent
    data class NavigateToConversationSettings(val conversationId: String) : ConversationListUiEvent
    data class NavigateToMessageInfo(val message: MessageUiModel) : ConversationListUiEvent
    data class ShowErrorMessage(val message: String) : ConversationListUiEvent
    data class ShowInfoMessage(val res: StringResource) : ConversationListUiEvent
    data class ShareText(val text: String) : ConversationListUiEvent
    data class ShareFile(val filePath: String) : ConversationListUiEvent
    data class OpenFile(val filePath: String) : ConversationListUiEvent
}
