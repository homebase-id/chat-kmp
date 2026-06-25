package id.homebase.chat.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

/**
 * Pins the "no event in the past" validation (#786): [isEventStartInPast] (the Send guard +
 * inline error) and [startOfTodayUtcMillis] (the start date picker's greyed-out lower bound).
 */
class EventStartValidationTest {

    private val utc = TimeZone.UTC
    private val ny = TimeZone.of("America/New_York") // UTC-4 in June (DST)
    private val now = Instant.parse("2026-06-25T12:00:00Z")

    @Test
    fun start_before_now_is_past() {
        assertTrue(isEventStartInPast(LocalDateTime(2026, 6, 25, 10, 0), utc, now))
    }

    @Test
    fun start_after_now_is_not_past() {
        assertFalse(isEventStartInPast(LocalDateTime(2026, 6, 25, 14, 0), utc, now))
    }

    @Test
    fun start_exactly_now_is_not_past() {
        // Strictly-before: an event starting at exactly "now" is allowed.
        assertFalse(isEventStartInPast(LocalDateTime(2026, 6, 25, 12, 0), utc, now))
    }

    @Test
    fun timezone_is_honored() {
        // 14:00 in New York (UTC-4) is 18:00Z — still in the future vs 12:00Z.
        assertFalse(isEventStartInPast(LocalDateTime(2026, 6, 25, 14, 0), ny, now))
        // 07:00 in New York is 11:00Z — before 12:00Z, so it's in the past.
        assertTrue(isEventStartInPast(LocalDateTime(2026, 6, 25, 7, 0), ny, now))
    }

    @Test
    fun today_lower_bound_is_utc_midnight_of_local_today() {
        // In UTC, "today" at 12:00Z is 2026-06-25 → its UTC midnight.
        val expected = LocalDate(2026, 6, 25).atStartOfDayIn(utc).toEpochMilliseconds()
        assertEquals(expected, startOfTodayUtcMillis(now, utc))
    }

    @Test
    fun today_lower_bound_respects_timezone_rollover() {
        // 01:00Z is still the previous day (21:00) in New York, so "today" there is Jun 24.
        val earlyUtc = Instant.parse("2026-06-25T01:00:00Z")
        val expected = LocalDate(2026, 6, 24).atStartOfDayIn(utc).toEpochMilliseconds()
        assertEquals(expected, startOfTodayUtcMillis(earlyUtc, ny))
    }

    @Test
    fun clamp_past_start_snaps_to_now_at_minute_granularity() {
        val nowWithSeconds = Instant.parse("2026-06-25T12:34:56Z")
        val past = LocalDateTime(2026, 6, 25, 8, 0)
        // Snapped to now, seconds dropped (the composer works at minute granularity).
        assertEquals(
            LocalDateTime(2026, 6, 25, 12, 34),
            clampStartToNow(past, utc, nowWithSeconds),
        )
    }

    @Test
    fun clamp_future_start_is_unchanged() {
        val future = LocalDateTime(2026, 6, 25, 18, 0)
        assertEquals(future, clampStartToNow(future, utc, now))
    }

    @Test
    fun clamp_start_exactly_now_is_unchanged() {
        // Not in the past (not strictly before), so it's returned as-is.
        val atNow = LocalDateTime(2026, 6, 25, 12, 0)
        assertEquals(atNow, clampStartToNow(atNow, utc, now))
    }

    @Test
    fun default_window_is_top_of_next_hour_plus_one() {
        val (start, end) = defaultEventWindow(Instant.parse("2026-06-25T12:30:00Z"), utc)
        assertEquals(LocalDateTime(2026, 6, 25, 13, 0), start)
        assertEquals(LocalDateTime(2026, 6, 25, 14, 0), end)
    }

    @Test
    fun default_window_never_collapses_in_the_late_evening() {
        // The bug: at/after 22:00 the old hour-clamp made start == end (Send disabled, "0m").
        // Instant math rolls past midnight instead, so end is always strictly after start.
        for (hour in 22..23) {
            val (start, end) = defaultEventWindow(Instant.parse("2026-06-25T${hour}:40:00Z"), utc)
            assertTrue(start < end, "start must be before end at $hour:40")
            // And the default start is never in the past.
            assertFalse(isEventStartInPast(start, utc, Instant.parse("2026-06-25T${hour}:40:00Z")))
        }
    }

    @Test
    fun default_window_at_2340_rolls_to_next_day() {
        val (start, end) = defaultEventWindow(Instant.parse("2026-06-25T23:40:00Z"), utc)
        assertEquals(LocalDateTime(2026, 6, 26, 0, 0), start)
        assertEquals(LocalDateTime(2026, 6, 26, 1, 0), end)
    }
}
