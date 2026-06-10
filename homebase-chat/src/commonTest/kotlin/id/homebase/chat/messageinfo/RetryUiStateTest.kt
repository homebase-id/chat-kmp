package id.homebase.chat.messageinfo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [retryUiState] — the outbox-row overlay on top of [deriveSendStatus]. A
 * pending outbox row is authoritative "still sending": Queued + Try-now wins
 * over both the Sending spinner and a (theoretically stale) Failed/Retry.
 */
class RetryUiStateTest {

    private val nowMs = 1_780_000_000_000L // a current-era ms epoch

    private val failedBase = SendStatusResult(OutgoingSendState.Failed, RetryMode.Create, canRetry = true)
    private val sendingBase = SendStatusResult(OutgoingSendState.Sending, null, canRetry = false)
    private val sentBase = SendStatusResult(OutgoingSendState.Sent, null, canRetry = false)

    // ---- no outbox row: base passes through ----

    @Test
    fun noRowPassesTheBaseThroughUntouched() {
        for (base in listOf(failedBase, sendingBase, sentBase)) {
            val result = retryUiState(base, inOutbox = false, isCheckedOut = false, nextRunTimeRaw = null, nowMs = nowMs)
            assertEquals(base.sendState, result.sendState)
            assertEquals(base.retryMode, result.retryMode)
            assertEquals(base.canRetry, result.canRetry)
            assertFalse(result.canTryNow)
            assertNull(result.nextAttemptInMinutes)
        }
    }

    // ---- row present: Queued is authoritative ----

    @Test
    fun queuedRowOverridesSendingWithTryNow() {
        val result = retryUiState(
            sendingBase, inOutbox = true, isCheckedOut = false,
            nextRunTimeRaw = nowMs + 10 * 60_000L, nowMs = nowMs,
        )
        assertEquals(OutgoingSendState.Queued, result.sendState)
        assertFalse(result.canRetry)
        assertTrue(result.canTryNow)
        assertEquals(10L, result.nextAttemptInMinutes)
    }

    @Test
    fun queuedRowSuppressesRetryEvenForAFailedBase() {
        val result = retryUiState(
            failedBase, inOutbox = true, isCheckedOut = false,
            nextRunTimeRaw = nowMs + 60_000L, nowMs = nowMs,
        )
        assertEquals(OutgoingSendState.Queued, result.sendState)
        assertFalse(result.canRetry, "a queued message must never offer Retry")
        assertNull(result.retryMode)
        assertTrue(result.canTryNow)
    }

    @Test
    fun checkedOutRowShowsNoTryNowAndNoDeadline() {
        val result = retryUiState(
            sendingBase, inOutbox = true, isCheckedOut = true,
            nextRunTimeRaw = nowMs + 60_000L, nowMs = nowMs,
        )
        assertEquals(OutgoingSendState.Queued, result.sendState)
        assertFalse(result.canTryNow, "resetting an in-flight upload is meaningless")
        assertNull(result.nextAttemptInMinutes)
    }

    // ---- deadline normalization ----

    @Test
    fun pastDeadlineFloorsToZeroMinutes() {
        val result = retryUiState(
            sendingBase, inOutbox = true, isCheckedOut = false,
            nextRunTimeRaw = nowMs - 5_000L, nowMs = nowMs,
        )
        assertEquals(0L, result.nextAttemptInMinutes)
    }

    @Test
    fun insertSentinelsAndLegacySecondsEpochsReadAsShortly() {
        // 0 and 42 are fresh-insert/clearCheckedOut sentinels; ~1.7e9 is a
        // legacy seconds-epoch row written before the checkInFailed unit fix.
        for (raw in listOf(0L, 42L, 1_780_000_000L)) {
            val result = retryUiState(
                sendingBase, inOutbox = true, isCheckedOut = false,
                nextRunTimeRaw = raw, nowMs = nowMs,
            )
            assertEquals(
                0L, result.nextAttemptInMinutes,
                "raw=$raw must normalize to 'shortly', not a year-count of minutes",
            )
            assertTrue(result.canTryNow)
        }
    }

    @Test
    fun subMinuteWaitRoundsDownToShortly() {
        val result = retryUiState(
            sendingBase, inOutbox = true, isCheckedOut = false,
            nextRunTimeRaw = nowMs + 45_000L, nowMs = nowMs,
        )
        assertEquals(0L, result.nextAttemptInMinutes)
    }
}
