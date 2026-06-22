package id.homebase.chat.conversationlist

import id.homebase.api.common.OdinId
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
    data class SaveFileToDevice(val filePath: String, val suggestedName: String) : ConversationListUiEvent
    data class OpenUrl(val url: String) : ConversationListUiEvent
    data class OpenSendConnectionRequestDialog(val odinId: OdinId) : ConversationListUiEvent

    /**
     * Bytes have been posted to [CropResultBus] under [requestId]; the screen
     * should navigate to the cropper.
     */
    data class NavigateToCropper(val requestId: kotlin.uuid.Uuid) : ConversationListUiEvent

    /**
     * Bytes have been posted to [DrawResultBus] under [requestId]; the screen
     * should navigate to the draw editor.
     */
    data class NavigateToDrawer(val requestId: kotlin.uuid.Uuid) : ConversationListUiEvent

    /** Open the Live Location map (from tapping a live location bubble's map). */
    data object NavigateToLiveLocationMap : ConversationListUiEvent

    /** Open the location setup screen (from the "set up location" prompt before a live share). */
    data object NavigateToLocationSetup : ConversationListUiEvent
}
