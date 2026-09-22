package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.connections.ConnectionStatus

/**
 * The single rule for "this incoming request is still mine to answer". Blocked is excluded
 * alongside Connected — blocking *is* the answer, so a blocked sender gets no Accept/Reject.
 */
fun isPendingIncomingRequest(
    status: ConnectionStatus?,
    hasIncomingRequest: Boolean,
): Boolean =
    hasIncomingRequest &&
        status != ConnectionStatus.Connected &&
        status != ConnectionStatus.Blocked
