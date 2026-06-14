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
            val result = retryUiState(base, inOutbox = false, isCheckedOut = false, isActivelyUploading = false, nextRunTimeRaw = null, nowMs = nowMs)
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
            sendingBase, inOutbox = true, isCheckedOut = false, isActivelyUploading = false,
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
            failedBase, inOutbox = true, isCheckedOut = false, isActivelyUploading = false,
            nextRunTimeRaw = nowMs + 60_000L, nowMs = nowMs,
        )
        assertEquals(OutgoingSendState.Queued, result.sendState)
        assertFalse(result.canRetry, "a queued message must never offer Retry")
        assertNull(result.retryMode)
        assertTrue(result.canTryNow)
    }

    @Test
    fun activelyUploadingRowShowsNoTryNowAndNoDeadline() {
        // A live worker is genuinely uploading it → resetting is meaningless.
        val result = retryUiState(
            sendingBase, inOutbox = true, isCheckedOut = true, isActivelyUploading = true,
            nextRunTimeRaw = nowMs + 60_000L, nowMs = nowMs,
        )
        assertEquals(OutgoingSendState.Queued, result.sendState)
        assertFalse(result.canTryNow, "resetting an actively-uploading row is meaningless")
        assertNull(result.nextAttemptInMinutes)
    }

    @Test
    fun zombieCheckoutOffersTryNow() {
        // Checked out in the DB but NO live worker is running it (worker died) —
        // Try-now is offered so it can be revived. No time guess: the signal is
        // simply "not actively uploading".
        val result = retryUiState(
            sendingBase, inOutbox = true, isCheckedOut = true, isActivelyUploading = false,
            nextRunTimeRaw = nowMs + 60_000L, nowMs = nowMs,
        )
        assertEquals(OutgoingSendState.Queued, result.sendState)
        assertTrue(result.canTryNow, "a zombie checkout must be revivable via Try now")
    }

    @Test
    fun settledBaseWithLingeringRowKeepsStatusButOffersTryNow() {
        // A delivered message can still carry a dead/lingering row — don't
        // relabel it "Queued"; keep Sent and just offer Try-now to clear it.
        val result = retryUiState(
            sentBase, inOutbox = true, isCheckedOut = false, isActivelyUploading = false,
            nextRunTimeRaw = nowMs + 60_000L, nowMs = nowMs,
        )
        assertEquals(OutgoingSendState.Sent, result.sendState, "a delivered message must not be relabelled Queued")
        assertTrue(result.canTryNow, "the lingering row is clearable via Try now")
    }

    // ---- deadline normalization ----

    @Test
    fun pastDeadlineFloorsToZeroMinutes() {
        val result = retryUiState(
            sendingBase, inOutbox = true, isCheckedOut = false, isActivelyUploading = false,
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
                sendingBase, inOutbox = true, isCheckedOut = false, isActivelyUploading = false,
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
            sendingBase, inOutbox = true, isCheckedOut = false, isActivelyUploading = false,
            nextRunTimeRaw = nowMs + 45_000L, nowMs = nowMs,
        )
        assertEquals(0L, result.nextAttemptInMinutes)
    }
}
