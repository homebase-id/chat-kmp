package id.homebase.core.auth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AuthConnectionCoordinatorHoldsTest {
    @Test
    fun notHeldUntilAReasonHolds() {
        val holds = WebSocketHolds()
        assertFalse(holds.held)
        assertTrue(holds.hold("call"))
        assertTrue(holds.held)
    }

    @Test
    fun holdIsIdempotentPerReasonAndReportsWhetherItChangedAnything() {
        val holds = WebSocketHolds()
        assertTrue(holds.hold("call"))
        assertFalse(holds.hold("call"))
        assertTrue(holds.release("call"))
        assertFalse(holds.release("call"))
    }

    @Test
    fun staysHeldWhileAnyReasonRemains() {
        val holds = WebSocketHolds()
        holds.hold("call")
        holds.hold("live-share")
        holds.release("call")
        assertTrue(holds.held)
        holds.release("live-share")
        assertFalse(holds.held)
    }
}
