package id.homebase.core.ui.screens.connections

import id.homebase.api.client.connections.IncomingConnectionRequestResponse
import id.homebase.api.client.connections.OutgoingConnectionRequestResponse
import id.homebase.api.client.identity.PublicIdentity
import id.homebase.api.common.OdinId

data class ConnectionsUiState(
    val isLoading: Boolean = false,
    val incomingRequests: List<IncomingConnectionRequestResponse> = emptyList(),
    val outgoingRequests: List<OutgoingConnectionRequestResponse> = emptyList(),
    val identities: Map<OdinId, PublicIdentity> = emptyMap(),
    val pendingOdinIds: Set<OdinId> = emptySet(),
    val showIntroductionOutgoing: Boolean = false,

    val showComposeDialog: Boolean = false,
    val composeRecipient: String = "",
    val composeMessage: String = "",
    val recipientResolution: RecipientResolution = RecipientResolution.Idle,
    val isSending: Boolean = false,

    val alreadySentRecipient: OdinId? = null,

    val uiEvent: ConnectionsUiEvent? = null,
)

sealed interface ConnectionsUiAction {
    data object Refresh : ConnectionsUiAction
    data class SetShowIntroductionOutgoing(val show: Boolean) : ConnectionsUiAction

    data object OpenComposeDialog : ConnectionsUiAction
    data object CloseComposeDialog : ConnectionsUiAction
    data class ComposeRecipientChanged(val value: String) : ConnectionsUiAction
    data class ComposeMessageChanged(val value: String) : ConnectionsUiAction
    data object SendClicked : ConnectionsUiAction

    data class AcceptIncoming(val senderOdinId: OdinId) : ConnectionsUiAction
    data class RejectIncoming(val senderOdinId: OdinId) : ConnectionsUiAction
    data class CancelOutgoing(val recipient: OdinId) : ConnectionsUiAction

    data object DismissAlreadySentDialog : ConnectionsUiAction
    data object OpenOwnerConsoleClicked : ConnectionsUiAction
}

sealed interface RecipientResolution {
    data object Idle : RecipientResolution
    data object InvalidFormat : RecipientResolution
    data object Resolving : RecipientResolution
    data class Resolved(val identity: PublicIdentity) : RecipientResolution
    data object NotFound : RecipientResolution
}

sealed interface ConnectionsUiEvent {
    data object SendSuccess : ConnectionsUiEvent
    data class SendError(val message: String) : ConnectionsUiEvent
    data class ActionError(val message: String) : ConnectionsUiEvent
    data class OpenUrl(val url: String) : ConnectionsUiEvent
}
