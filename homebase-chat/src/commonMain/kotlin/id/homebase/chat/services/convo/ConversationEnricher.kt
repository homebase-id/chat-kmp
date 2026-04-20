package id.homebase.chat.services.convo

import co.touchlab.kermit.Logger
import id.homebase.api.client.auth.OwnerSession
import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.api.client.connections.RedactedIdentityConnectionRegistration
import id.homebase.api.common.OdinId
import id.homebase.chat.data.*
import id.homebase.chat.services.convo.contact.ContactConnectionState

class ConversationEnricher {

    /**
     * Enriches a basic [ConversationUiModel] with resolved contact/
     * connection data for display.
     *
     * Tolerates a null [ownerSession] — if the session hasn't loaded yet,
     * the enricher falls back to `credentialsManager.requireActiveDomain()`
     * semantics (the "other participant" filter just can't exclude the
     * current user, which is safe for a first-paint render). The list
     * screen pushes a null session through so that cold-load rendering
     * never blocks on session resolution.
     *
     * Empty `contactMap` / `connectionMap` inputs are also tolerated —
     * display names fall back to `odinId.domainName` at the UI layer.
     */
    fun enrich(
        convo: ConversationUiModel,
        contactMap: Map<OdinId, ContactUiModel>,
        ownerSession: OwnerSession?,
        connectionMap: Map<OdinId, RedactedIdentityConnectionRegistration> = emptyMap(),
        incomingRequestSenders: Set<OdinId> = emptySet(),
        outgoingRequestRecipients: Set<OdinId> = emptySet(),
        connectionStatusKnown: Boolean = true,
    ): EnrichedConversationUiModel {

        val currentUser = ownerSession?.odinId

        if (convo.isWithSelf) {
            return EnrichedConversationUiModel(
                conversation = convo,
                participants = emptyList(),
                missingConnections = emptyList()
            )
        }

        val otherParticipants = if (currentUser != null) {
            convo.participants.filter { it != currentUser }
        } else {
            // Session not loaded yet — render with best-effort participants.
            // Subsequent emits will re-enrich once the session resolves.
            convo.participants
        }

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

        val oneOnOneConnectionStatus = if (otherParticipants.size == 1) {
            val other = otherParticipants.first()
            val connection = connectionMap[other]
            val connected = connection?.status == ConnectionStatus.Connected

            val status = when {
                connected -> OneOnOneConnectionStatus.Connected(other)
                incomingRequestSenders.contains(other) ->
                    OneOnOneConnectionStatus.IncomingRequestPending(other)
                outgoingRequestRecipients.contains(other) ->
                    OneOnOneConnectionStatus.OutgoingRequestPending(other)
                !connectionStatusKnown -> OneOnOneConnectionStatus.Unknown(other)
                else -> OneOnOneConnectionStatus.NotConnected(other)
            }
            if (status !is OneOnOneConnectionStatus.Connected) {
                Logger.d(tag = "ConversationEnricher") {
                    "1:1 convo=${convo.id} other=$other " +
                            "connectionStatus=${connection?.status} " +
                            "inIncoming=${incomingRequestSenders.contains(other)} " +
                            "inOutgoing=${outgoingRequestRecipients.contains(other)} " +
                            "incomingSet=$incomingRequestSenders " +
                            "outgoingSet=$outgoingRequestRecipients " +
                            "-> $status"
                }
            }
            status
        } else null

        return EnrichedConversationUiModel(
            conversation = convo,
            participants = participants,
            missingConnections = missingConnections,
            oneOnOneConnectionStatus = oneOnOneConnectionStatus,
        )
    }
}
