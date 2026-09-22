package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.connections.ConnectionStatus
import id.homebase.core.ui.screens.contactbook.detail.ContactDetailUiState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingIncomingRequestTest {

    @Test
    fun `no incoming request is never pending`() {
        assertFalse(isPendingIncomingRequest(null, hasIncomingRequest = false))
        assertFalse(isPendingIncomingRequest(ConnectionStatus.None, hasIncomingRequest = false))
    }

    @Test
    fun `an unconnected sender is pending`() {
        assertTrue(isPendingIncomingRequest(null, hasIncomingRequest = true))
        assertTrue(isPendingIncomingRequest(ConnectionStatus.None, hasIncomingRequest = true))
    }

    @Test
    fun `a connected sender is not pending`() {
        assertFalse(isPendingIncomingRequest(ConnectionStatus.Connected, hasIncomingRequest = true))
    }

    @Test
    fun `a blocked sender is not pending`() {
        assertFalse(isPendingIncomingRequest(ConnectionStatus.Blocked, hasIncomingRequest = true))
    }

    @Test
    fun `contact detail agrees with the rule for a blocked sender`() {
        val blocked = ContactDetailUiState(
            connectionStatus = ConnectionStatus.Blocked,
            requestDirection = RequestDirection.INCOMING,
        )
        assertFalse(blocked.isPendingIncoming)
        assertTrue(blocked.copy(connectionStatus = null).isPendingIncoming)
    }

    @Test
    fun `an outgoing request is not pending incoming`() {
        val outgoing = ContactDetailUiState(requestDirection = RequestDirection.OUTGOING)
        assertFalse(outgoing.isPendingIncoming)
    }
}
