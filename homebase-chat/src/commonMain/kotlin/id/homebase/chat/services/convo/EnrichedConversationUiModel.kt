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
    fun getDisplayName(youLabel: String = "You"): String {
        // Note-to-self: the single "participant" is the owner, so a name lookup
        // would surface the owner's own domain. Label it with [youLabel] instead.
        if (conversation.isWithSelf) return youLabel

        if (!conversation.isGroupConversation) {
            participants.firstOrNull()?.let {
                return it.name
            }
            return conversation.getDisplayName()
        }

        conversation.name.takeIf { it.isNotBlank() }?.let { return it }

        // Untitled group: prepend `youLabel` so a 2-person group visually differs from
        // a 1:1 with the same person (which would just render "Alice").
        val names = listOf(youLabel) + participants.map { it.name }.filter { it.isNotBlank() }
        val joined = names.joinToString(", ")
        if (joined.isNotBlank()) return joined

        return conversation.getDisplayName()
    }
}

/**
 * True when [query] matches the conversation's display name, or — so a group is
 * findable by a member when its title isn't memorable — any participant's contact
 * name. Case-insensitive. Callers gate on a non-empty query.
 */
internal fun EnrichedConversationUiModel.matchesConversationQuery(query: String): Boolean =
    getDisplayName().contains(query, ignoreCase = true) ||
        participants.any { it.name.contains(query, ignoreCase = true) }

/** Connection state for the other party in a 1:1 conversation. `null` for groups,
 *  note-to-self, or while the status is still being resolved. */
sealed interface OneOnOneConnectionStatus {
    val otherOdinId: OdinId

    data class Connected(override val otherOdinId: OdinId) : OneOnOneConnectionStatus
    data class NotConnected(override val otherOdinId: OdinId) : OneOnOneConnectionStatus
    data class OutgoingRequestPending(override val otherOdinId: OdinId) : OneOnOneConnectionStatus
    data class IncomingRequestPending(override val otherOdinId: OdinId) : OneOnOneConnectionStatus

    /** We have no cached or live connection data for this identity (e.g. first launch
     *  while offline). Don't assert "Not connected" — show nothing, since a wrong
     *  negative is worse than no pill. */
    data class Unknown(override val otherOdinId: OdinId) : OneOnOneConnectionStatus
}
