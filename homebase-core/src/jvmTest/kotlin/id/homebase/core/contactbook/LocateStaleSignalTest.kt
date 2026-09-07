package id.homebase.core.contactbook

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocateStaleSignalTest {

    private val now = 10L * 24 * 60 * 60_000L

    @Test
    fun thresholdIsTwoDays() {
        assertEquals(2L * 24 * 60 * 60_000L, LOCATE_STALE_WARN_MS)
    }

    @Test
    fun exactlyTwoDaysIsNotStale() {
        assertFalse(locateSignalStale(now - LOCATE_STALE_WARN_MS, now))
    }

    @Test
    fun oneMillisecondPastTwoDaysIsStale() {
        assertTrue(locateSignalStale(now - LOCATE_STALE_WARN_MS - 1, now))
    }

    @Test
    fun neverProducedDataIsNotStale() {
        assertFalse(locateSignalStale(null, now))
    }

    @Test
    fun peerClockAheadIsNotStale() {
        assertFalse(locateSignalStale(now + 60_000L, now))
    }
}
