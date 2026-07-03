package id.homebase.core.ui.screens.location

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/**
 * Pins the "who I can locate" freshness-label rules (#879): the compact age bucketing
 * ([locateAgeBucket] — minutes under 1 h, hours through 96 h, then days) and the warning-color
 * threshold ([locateAgeWarn] — strictly older than 2 h renders orange).
 */
class LocateAgeLabelTest {

    // ---- bucketing ----

    @Test
    fun underAnHourIsMinutes() {
        assertEquals(LocateAgeBucket.Minutes(0), locateAgeBucket(0))
        assertEquals(LocateAgeBucket.Minutes(25), locateAgeBucket(25 * MINUTE_MS))
        assertEquals(LocateAgeBucket.Minutes(59), locateAgeBucket(60 * MINUTE_MS - 1))
    }

    @Test
    fun negativeAgeClampsToZeroMinutes() {
        // A peer clock slightly ahead of ours must not render "-1m".
        assertEquals(LocateAgeBucket.Minutes(0), locateAgeBucket(-5 * MINUTE_MS))
    }

    @Test
    fun anHourThrough96HoursIsHours() {
        assertEquals(LocateAgeBucket.Hours(1), locateAgeBucket(HOUR_MS))
        assertEquals(LocateAgeBucket.Hours(2), locateAgeBucket(2 * HOUR_MS))
        assertEquals(LocateAgeBucket.Hours(96), locateAgeBucket(96 * HOUR_MS))
    }

    @Test
    fun past96HoursIsDays() {
        assertEquals(LocateAgeBucket.Days(4), locateAgeBucket(96 * HOUR_MS + 1))
        assertEquals(LocateAgeBucket.Days(10), locateAgeBucket(10 * DAY_MS))
    }

    // ---- warning threshold (orange > 2 h) ----

    @Test
    fun exactlyTwoHoursIsNotYetWarned() {
        assertFalse(locateAgeWarn(LOCATE_AGE_WARN_MS))
    }

    @Test
    fun justPastTwoHoursWarns() {
        assertTrue(locateAgeWarn(LOCATE_AGE_WARN_MS + 1))
    }

    @Test
    fun freshAndFarStaleSanity() {
        assertFalse(locateAgeWarn(25 * MINUTE_MS))
        assertTrue(locateAgeWarn(3 * DAY_MS))
    }

    @Test
    fun warnThresholdIsTwoHours() {
        assertEquals(2 * HOUR_MS, LOCATE_AGE_WARN_MS)
    }
}
