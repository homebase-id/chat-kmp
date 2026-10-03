package id.homebase.core.ui.screens.contactbook

import id.homebase.api.client.connections.ConnectionStatus
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
}
