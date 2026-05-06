package id.homebase.core.notifications

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Locks down the bit-mask check that detects when MainActivity is being
 * re-resumed from the recents/history stack with a stale notification
 * Intent (the symptom in homebase.log around 2026-05-01 08:05:49).
 *
 * Mirrors `Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` (0x00100000).
 * The `Intent` constant itself is Android-only; the helper takes an Int
 * so it's unit-testable from JVM without Robolectric.
 */
class IsReplayedFromHistoryTest {

    private val FLAG_HISTORY = 0x00100000           // Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
    private val FLAG_NEW_TASK = 0x10000000          // Intent.FLAG_ACTIVITY_NEW_TASK
    private val FLAG_CLEAR_TOP = 0x04000000         // Intent.FLAG_ACTIVITY_CLEAR_TOP

    @Test
    fun launchedFromHistoryOnly_returnsTrue() {
        assertTrue(isReplayedFromHistory(FLAG_HISTORY))
    }

    @Test
    fun launchedFromHistoryCombinedWithOthers_returnsTrue() {
        assertTrue(isReplayedFromHistory(FLAG_HISTORY or FLAG_NEW_TASK or FLAG_CLEAR_TOP))
    }

    @Test
    fun otherFlagsOnly_returnsFalse() {
        assertFalse(isReplayedFromHistory(FLAG_NEW_TASK or FLAG_CLEAR_TOP))
    }

    @Test
    fun zeroFlags_returnsFalse() {
        assertFalse(isReplayedFromHistory(0))
    }

    @Test
    fun helperConstantMatchesAndroidValue() {
        // Pin the constant — if someone mistypes it the bit-mask quietly stops working.
        assertTrue(FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0x00100000)
    }
}
