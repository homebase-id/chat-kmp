package id.homebase.chat.messageinfo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [nextAttemptDeadlineMs] — the absolute ms-epoch the Message Info live
 * countdown ticks toward, or null ("shortly") when there's no meaningful
 * deadline. Mirrors the normalization [retryUiState] applies to minutes.
 */
class NextAttemptDeadlineMsTest {

    private val nowMs = 1_780_000_000_000L // a current-era ms epoch

    @Test
    fun checkedOutRowHasNoDeadline() {
        assertNull(nextAttemptDeadlineMs(isCheckedOut = true, nextRunTimeRaw = nowMs + 60_000L))
    }

    @Test
    fun nullRawHasNoDeadline() {
        assertNull(nextAttemptDeadlineMs(isCheckedOut = false, nextRunTimeRaw = null))
    }

    @Test
    fun insertSentinelsAndLegacySecondsEpochsHaveNoDeadline() {
        // 0 / 42 are fresh-insert sentinels; ~1.7e9 is a legacy seconds-epoch
        // row — none is a plausible current ms-epoch, so render "shortly".
        for (raw in listOf(0L, 42L, 1_780_000_000L)) {
            assertNull(
                nextAttemptDeadlineMs(isCheckedOut = false, nextRunTimeRaw = raw),
                "raw=$raw must have no countdown deadline",
            )
        }
    }

    @Test
    fun plausibleFutureEpochIsReturnedVerbatim() {
        val deadline = nowMs + 5 * 60_000L
        assertEquals(deadline, nextAttemptDeadlineMs(isCheckedOut = false, nextRunTimeRaw = deadline))
    }

    @Test
    fun plausiblePastEpochIsReturnedVerbatim() {
        // Still a real ms-epoch (above the sentinel floor); the UI coerces the
        // remaining span to zero and shows "shortly".
        val deadline = nowMs - 5_000L
        assertEquals(deadline, nextAttemptDeadlineMs(isCheckedOut = false, nextRunTimeRaw = deadline))
    }
}
