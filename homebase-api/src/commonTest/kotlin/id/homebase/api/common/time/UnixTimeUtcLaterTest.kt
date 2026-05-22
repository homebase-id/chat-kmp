package id.homebase.api.common.time

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [UnixTimeUtc.later] — the nullable monotonic-max used by the lastRead
 * writeback to ride the conversation-list sort key along without ever regressing
 * it (a device behind on sync must not stamp an older time over a newer one).
 */
class UnixTimeUtcLaterTest {

    @Test
    fun bothNullReturnsNull() {
        assertNull(UnixTimeUtc.later(null, null))
    }

    @Test
    fun oneNullReturnsTheOther() {
        val t = UnixTimeUtc(100)
        assertEquals(t, UnixTimeUtc.later(t, null))
        assertEquals(t, UnixTimeUtc.later(null, t))
    }

    @Test
    fun bothNonNullReturnsTheLarger() {
        val lo = UnixTimeUtc(100)
        val hi = UnixTimeUtc(200)
        assertEquals(hi, UnixTimeUtc.later(lo, hi))
        assertEquals(hi, UnixTimeUtc.later(hi, lo))
        assertEquals(hi, UnixTimeUtc.later(hi, hi))
    }
}
