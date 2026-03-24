package id.homebase.chat.services.convo

import androidx.compose.runtime.Immutable
import id.homebase.api.common.OdinId
import id.homebase.chat.data.ContactUiModel
import id.homebase.chat.data.ConversationUiModel

@Immutable
data class EnrichedConversationUiModel(
    val conversation: ConversationUiModel,
    val participants: List<ContactUiModel>,
    val missingConnections: List<OdinId>,
) {
    fun getDisplayName(): String {
        if (!conversation.isGroupConversation) {
             participants.firstOrNull()?.let {
                return it.name
            }
        }
        return conversation.getDisplayName()
    }
}
