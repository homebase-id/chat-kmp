package id.homebase.chat.messageinfo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the [deriveSendStatus] scenario matrix that drives the Message Info
 * status row + retry stub. See MessageInfoUiState.kt.
 */
class DeriveSendStatusTest {

    // --- Inbound: never a status row, never a retry ------------------------

    @Test
    fun inbound_neverShowsStatusOrRetry() {
        for (presence in ServerPresence.entries) {
            for (deliveryFailed in listOf(false, true)) {
                val r = deriveSendStatus(
                    isOwnMessage = false,
                    isPendingSend = false,
                    isFailedSend = false,
                    deliveryFailed = deliveryFailed,
                    serverPresence = presence,
                )
                assertNull(r.sendState, "inbound should have no status row")
                assertNull(r.retryMode)
                assertFalse(r.canRetry)
            }
        }
    }

    // --- Failed send: always Failed + retry; intent depends on presence ----

    @Test
    fun failed_absent_isFailedWithCreateRetry() {
        val r = deriveSendStatus(
            isOwnMessage = true,
            isPendingSend = false,
            isFailedSend = true,
            deliveryFailed = false,
            serverPresence = ServerPresence.Absent,
        )
        assertEquals(OutgoingSendState.Failed, r.sendState)
        assertEquals(RetryMode.Create, r.retryMode)
        assertTrue(r.canRetry)
    }

    @Test
    fun failed_present_isFailedWithUpdateRetry() {
        val r = deriveSendStatus(
            isOwnMessage = true,
            isPendingSend = false,
            isFailedSend = true,
            deliveryFailed = false,
            serverPresence = ServerPresence.Present,
        )
        assertEquals(OutgoingSendState.Failed, r.sendState)
        assertEquals(RetryMode.Update, r.retryMode)
        assertTrue(r.canRetry)
    }

    @Test
    fun failed_unknown_isFailedWithUpdateRetry() {
        val r = deriveSendStatus(
            isOwnMessage = true,
            isPendingSend = false,
            isFailedSend = true,
            deliveryFailed = false,
            serverPresence = ServerPresence.Unknown,
        )
        assertEquals(OutgoingSendState.Failed, r.sendState)
        assertEquals(RetryMode.Update, r.retryMode)
        assertTrue(r.canRetry)
    }

    // --- Pending send: Sent if server already has it (race), else Sending --

    @Test
    fun pending_present_isSent_quickSwipeRace() {
        val r = deriveSendStatus(
            isOwnMessage = true,
            isPendingSend = true,
            isFailedSend = false,
            deliveryFailed = false,
            serverPresence = ServerPresence.Present,
        )
        assertEquals(OutgoingSendState.Sent, r.sendState)
        assertFalse(r.canRetry)
        assertNull(r.retryMode)
    }

    @Test
    fun pending_absent_isSending() {
        val r = deriveSendStatus(
            isOwnMessage = true,
            isPendingSend = true,
            isFailedSend = false,
            deliveryFailed = false,
            serverPresence = ServerPresence.Absent,
        )
        assertEquals(OutgoingSendState.Sending, r.sendState)
        assertFalse(r.canRetry)
    }

    @Test
    fun pending_unknown_isSending() {
        val r = deriveSendStatus(
            isOwnMessage = true,
            isPendingSend = true,
            isFailedSend = false,
            deliveryFailed = false,
            serverPresence = ServerPresence.Unknown,
        )
        assertEquals(OutgoingSendState.Sending, r.sendState)
        assertFalse(r.canRetry)
    }

    // --- Settled own message: just Sent ------------------------------------

    @Test
    fun settled_isSentNoRetry() {
        val r = deriveSendStatus(
            isOwnMessage = true,
            isPendingSend = false,
            isFailedSend = false,
            deliveryFailed = false,
            serverPresence = ServerPresence.Unknown,
        )
        assertEquals(OutgoingSendState.Sent, r.sendState)
        assertFalse(r.canRetry)
        assertNull(r.retryMode)
    }

    // --- Settled + recipient-level failure: DeliveryFailed, no retry -------

    @Test
    fun settled_deliveryFailed_isDeliveryFailedNoRetry() {
        for (presence in ServerPresence.entries) {
            val r = deriveSendStatus(
                isOwnMessage = true,
                isPendingSend = false,
                isFailedSend = false,
                deliveryFailed = true,
                serverPresence = presence,
            )
            assertEquals(OutgoingSendState.DeliveryFailed, r.sendState)
            assertNull(r.retryMode)
            assertFalse(r.canRetry)
        }
    }

    // --- Local tags win over recipient-level delivery state ----------------

    @Test
    fun failedSend_winsOverDeliveryFailed() {
        val r = deriveSendStatus(
            isOwnMessage = true,
            isPendingSend = false,
            isFailedSend = true,
            deliveryFailed = true,
            serverPresence = ServerPresence.Absent,
        )
        assertEquals(OutgoingSendState.Failed, r.sendState)
        assertTrue(r.canRetry)
    }

    @Test
    fun pending_winsOverDeliveryFailed() {
        val r = deriveSendStatus(
            isOwnMessage = true,
            isPendingSend = true,
            isFailedSend = false,
            deliveryFailed = true,
            serverPresence = ServerPresence.Absent,
        )
        assertEquals(OutgoingSendState.Sending, r.sendState)
        assertFalse(r.canRetry)
    }

    // --- Failed wins over pending if both tags somehow present -------------

    @Test
    fun failedTakesPrecedenceOverPending() {
        val r = deriveSendStatus(
            isOwnMessage = true,
            isPendingSend = true,
            isFailedSend = true,
            deliveryFailed = false,
            serverPresence = ServerPresence.Absent,
        )
        assertEquals(OutgoingSendState.Failed, r.sendState)
        assertTrue(r.canRetry)
    }
}
