package id.homebase.chat.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone

/**
 * Pins [eventDurationParts] — the pure helper behind the composer's hyperlinked duration
 * label (#786): minutes between start and end, and whether they fall on the same calendar
 * day (same-day → render a duration, multi-day → render the end date+time).
 */
class EventDurationPartsTest {

    private val utc = TimeZone.UTC

    @Test
    fun default_one_hour_is_sixty_minutes_same_day() {
        val parts = eventDurationParts(
            start = LocalDateTime(2026, 6, 16, 10, 0),
            end = LocalDateTime(2026, 6, 16, 11, 0),
            tz = utc,
        )
        assertEquals(60, parts.totalMinutes)
        assertTrue(parts.sameDay)
    }

    @Test
    fun ninety_minutes_same_day() {
        val parts = eventDurationParts(
            start = LocalDateTime(2026, 6, 16, 10, 0),
            end = LocalDateTime(2026, 6, 16, 11, 30),
            tz = utc,
        )
        assertEquals(90, parts.totalMinutes)
        assertTrue(parts.sameDay)
    }

    @Test
    fun fifteen_minutes_same_day() {
        val parts = eventDurationParts(
            start = LocalDateTime(2026, 6, 16, 10, 0),
            end = LocalDateTime(2026, 6, 16, 10, 15),
            tz = utc,
        )
        assertEquals(15, parts.totalMinutes)
        assertTrue(parts.sameDay)
    }

    @Test
    fun crossing_midnight_is_not_same_day() {
        // 23:00 → 01:00 next day: two hours, but a different calendar day.
        val parts = eventDurationParts(
            start = LocalDateTime(2026, 6, 16, 23, 0),
            end = LocalDateTime(2026, 6, 17, 1, 0),
            tz = utc,
        )
        assertEquals(120, parts.totalMinutes)
        assertFalse(parts.sameDay)
    }

    @Test
    fun multi_day_span() {
        val parts = eventDurationParts(
            start = LocalDateTime(2026, 6, 16, 10, 0),
            end = LocalDateTime(2026, 6, 18, 10, 0),
            tz = utc,
        )
        assertEquals(2 * 24 * 60L, parts.totalMinutes)
        assertFalse(parts.sameDay)
    }
}
