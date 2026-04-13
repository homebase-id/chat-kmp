package id.homebase.chat.services.convo

import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.common.OdinId
import id.homebase.chat.data.*
import id.homebase.chat.services.convo.contact.ContactConnectionState

class ConversationEnricher {

    fun enrich(
        convo: ConversationUiModel,
        contactMap: Map<OdinId, ContactUiModel>,
        ownerSession: OwnerSession
    ): EnrichedConversationUiModel {

        val currentUser = ownerSession.odinId

        // LEGACY NOTE TO SELF — isWithSelf check can be removed once legacy note-to-self is removed
        if (convo.isAnySelfConversation) {
            return EnrichedConversationUiModel(
                conversation = convo,
                participants = emptyList(),
                missingConnections = emptyList()
            )
        }

        val otherParticipants = convo.participants
            .filter { it != currentUser }

        val participants = otherParticipants.mapNotNull { odinId ->
            contactMap[odinId]
        }

        val missingConnections =
            if (otherParticipants.size > 1) {
                otherParticipants.filter { odinId ->
                    val contact = contactMap[odinId]

                    contact == null ||
                            contact.connectionState != ContactConnectionState.Connected
                }
            } else {
                emptyList()
            }

        return EnrichedConversationUiModel(
            conversation = convo,
            participants = participants,
            missingConnections = missingConnections
        )
    }
}