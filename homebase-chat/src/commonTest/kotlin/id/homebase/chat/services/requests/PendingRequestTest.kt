package id.homebase.chat.services.requests

import id.homebase.api.client.connections.ConnectionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PendingRequestTest {

    @Test
    fun noRequestIsNoDirection() {
        assertNull(pendingRequestDirection(null, hasIncoming = false, hasOutgoing = false))
        assertNull(pendingRequestDirection(ConnectionStatus.None, hasIncoming = false, hasOutgoing = false))
    }

    @Test
    fun anUnconnectedIdentityKeepsItsRequest() {
        assertEquals(RequestDirection.INCOMING, pendingRequestDirection(null, hasIncoming = true, hasOutgoing = false))
        assertEquals(RequestDirection.OUTGOING, pendingRequestDirection(ConnectionStatus.None, hasIncoming = false, hasOutgoing = true))
    }

    @Test
    fun incomingBeatsOutgoing() {
        assertEquals(RequestDirection.INCOMING, pendingRequestDirection(null, hasIncoming = true, hasOutgoing = true))
    }

    @Test
    fun connectedOrBlockedHasNoRequestEitherWay() {
        for (status in listOf(ConnectionStatus.Connected, ConnectionStatus.Blocked)) {
            assertNull(pendingRequestDirection(status, hasIncoming = true, hasOutgoing = false))
            assertNull(pendingRequestDirection(status, hasIncoming = false, hasOutgoing = true))
            assertNull(pendingRequestDirection(status, hasIncoming = true, hasOutgoing = true))
        }
    }
}
