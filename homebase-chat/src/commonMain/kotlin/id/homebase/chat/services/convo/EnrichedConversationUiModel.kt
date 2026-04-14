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
    val oneOnOneConnectionStatus: OneOnOneConnectionStatus? = null,
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

/** Connection state for the other party in a 1:1 conversation. `null` for groups,
 *  note-to-self, or while the status is still being resolved. */
sealed interface OneOnOneConnectionStatus {
    val otherOdinId: OdinId

    data class Connected(override val otherOdinId: OdinId) : OneOnOneConnectionStatus
    data class NotConnected(override val otherOdinId: OdinId) : OneOnOneConnectionStatus
    data class OutgoingRequestPending(override val otherOdinId: OdinId) : OneOnOneConnectionStatus
    data class IncomingRequestPending(override val otherOdinId: OdinId) : OneOnOneConnectionStatus
}
