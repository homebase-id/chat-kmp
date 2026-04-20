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

    val uiEvent: ConnectionsUiEvent? = null,
)

sealed interface ConnectionsUiAction {
    data object Refresh : ConnectionsUiAction
    data object OpenOwnerConsoleClicked : ConnectionsUiAction
}

sealed interface ConnectionsUiEvent {
    data class ActionError(val message: String) : ConnectionsUiEvent
    data class OpenUrl(val url: String) : ConnectionsUiEvent
}
