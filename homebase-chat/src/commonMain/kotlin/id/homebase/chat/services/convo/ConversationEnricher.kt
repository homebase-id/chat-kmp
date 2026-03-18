package id.homebase.chat.services.convo

import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.common.OdinId
import id.homebase.chat.data.*

class ConversationEnricher {

    fun enrich(
        convo: ConversationUiModel,
        contactMap: Map<OdinId, ContactUiModel>,
        ownerSession: OwnerSession
    ): EnrichedConversationUiModel {

        val currentUser = ownerSession.odinId

        val otherParticipants = convo.participants
            .filter { it != currentUser }

        val participants = otherParticipants.mapNotNull { odinId ->
            contactMap[odinId]
        }

        val missingConnections =
            if (otherParticipants.size > 1) {
                otherParticipants.filter { odinId ->
                    contactMap[odinId] == null
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