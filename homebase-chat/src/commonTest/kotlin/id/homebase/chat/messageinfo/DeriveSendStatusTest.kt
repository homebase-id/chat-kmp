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
            val r = deriveSendStatus(
                isOwnMessage = false,
                isPendingSend = false,
                isFailedSend = false,
                serverPresence = presence,
            )
            assertNull(r.sendState, "inbound should have no status row")
            assertNull(r.retryMode)
            assertFalse(r.canRetry)
        }
    }

    // --- Failed send: always Failed + retry; intent depends on presence ----

    @Test
    fun failed_absent_isFailedWithCreateRetry() {
        val r = deriveSendStatus(
            isOwnMessage = true,
            isPendingSend = false,
            isFailedSend = true,
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
            serverPresence = ServerPresence.Unknown,
        )
        assertEquals(OutgoingSendState.Sent, r.sendState)
        assertFalse(r.canRetry)
        assertNull(r.retryMode)
    }

    // --- Failed wins over pending if both tags somehow present -------------

    @Test
    fun failedTakesPrecedenceOverPending() {
        val r = deriveSendStatus(
            isOwnMessage = true,
            isPendingSend = true,
            isFailedSend = true,
            serverPresence = ServerPresence.Absent,
        )
        assertEquals(OutgoingSendState.Failed, r.sendState)
        assertTrue(r.canRetry)
    }
}
