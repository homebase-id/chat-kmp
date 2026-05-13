package id.homebase.notifshared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.datetime.TimeZone

class EventNotificationFormatterTest {

    private val utc = TimeZone.UTC

    // 2026-05-14 19:30:00 UTC (a Thursday)
    private val mayThursday7_30PmUtcMs = 1778787000000L
    // 2026-05-12 12:00:00 UTC (a Tuesday — useful as "now")
    private val mayTuesdayNoonUtcMs = 1778587200000L

    @Test
    fun parses_token_with_no_group_suffix() {
        val parsed = parseEventNotificationToken("__hb_event__|1715716200000")
        assertEquals(ParsedEventNotif(startUtcMs = 1715716200000L, groupSuffix = null), parsed)
    }

    @Test
    fun parses_token_with_group_suffix() {
        val parsed = parseEventNotificationToken("__hb_event__|1715716200000 in Family Group")
        assertEquals(
            ParsedEventNotif(startUtcMs = 1715716200000L, groupSuffix = "Family Group"),
            parsed
        )
    }

    @Test
    fun parses_token_with_group_name_that_contains_in() {
        // The group name itself contains the literal " in " — the regex
        // must capture the whole thing after the first " in " separator.
        val parsed = parseEventNotificationToken("__hb_event__|1715716200000 in Drinks in Brooklyn")
        assertEquals(
            ParsedEventNotif(startUtcMs = 1715716200000L, groupSuffix = "Drinks in Brooklyn"),
            parsed
        )
    }

    @Test
    fun returns_null_for_non_event_text() {
        assertNull(parseEventNotificationToken("hello"))
        assertNull(parseEventNotificationToken("__hb_event__|"))
        assertNull(parseEventNotificationToken("__hb_event__|notanumber"))
        assertNull(parseEventNotificationToken("some prefix __hb_event__|123"))
    }

    @Test
    fun formats_starting_now_when_within_one_minute() {
        val start = mayThursday7_30PmUtcMs
        val now = start - 30_000L // 30 seconds before
        assertEquals(
            "Event starting now",
            formatEventNotificationBody(start, now, utc)
        )
    }

    @Test
    fun formats_starting_now_when_just_started() {
        val start = mayThursday7_30PmUtcMs
        val now = start + 30 * 60_000L // 30 min after start
        assertEquals(
            "Event starting now",
            formatEventNotificationBody(start, now, utc)
        )
    }

    @Test
    fun formats_minutes_when_under_one_hour() {
        val start = mayThursday7_30PmUtcMs
        assertEquals(
            "Event in 5 minutes",
            formatEventNotificationBody(start, start - 5 * 60_000L, utc)
        )
        assertEquals(
            "Event in 1 minute",
            formatEventNotificationBody(start, start - 90_000L, utc)
        )
        assertEquals(
            "Event in 59 minutes",
            formatEventNotificationBody(start, start - 59 * 60_000L, utc)
        )
    }

    @Test
    fun formats_hours_when_under_one_day() {
        val start = mayThursday7_30PmUtcMs
        assertEquals(
            "Event in 1 hour",
            formatEventNotificationBody(start, start - 60 * 60_000L, utc)
        )
        // 1h 30m rounds up to 2 hours
        assertEquals(
            "Event in 2 hours",
            formatEventNotificationBody(start, start - 90 * 60_000L, utc)
        )
        // 23h 30m caps to 23 hours (the 24h+ branch handles 24+)
        assertEquals(
            "Event in 23 hours",
            formatEventNotificationBody(start, start - (23 * 60 + 45) * 60_000L, utc)
        )
    }

    @Test
    fun formats_absolute_future_date_when_at_least_one_day_away() {
        val start = mayThursday7_30PmUtcMs // Thursday May 14
        val now = mayTuesdayNoonUtcMs       // Tuesday May 12 noon — ~55 hours earlier
        assertEquals(
            "Event on Thu, May 14",
            formatEventNotificationBody(start, now, utc)
        )
    }

    @Test
    fun formats_absolute_past_date_when_more_than_one_hour_ago() {
        val start = mayThursday7_30PmUtcMs // Thursday May 14 7:30 PM UTC
        val now = start + 3 * 60 * 60_000L // 3 hours later
        assertEquals(
            "Event on Thu, May 14",
            formatEventNotificationBody(start, now, utc)
        )
    }

    @Test
    fun tryFormat_returns_null_for_non_event_input() {
        assertNull(tryFormatEventNotificationBody("hello", mayTuesdayNoonUtcMs, utc))
    }

    @Test
    fun tryFormat_appends_group_suffix() {
        val start = mayThursday7_30PmUtcMs
        val now = start - 30 * 60_000L // 30 min before start
        assertEquals(
            "Event in 30 minutes in Family Group",
            tryFormatEventNotificationBody(
                raw = "${EVENT_NOTIF_SENTINEL}$start in Family Group",
                nowMs = now,
                viewerTz = utc,
            )
        )
    }

    @Test
    fun tryFormat_omits_suffix_for_one_to_one_chat() {
        val start = mayThursday7_30PmUtcMs
        val now = mayTuesdayNoonUtcMs
        assertEquals(
            "Event on Thu, May 14",
            tryFormatEventNotificationBody(
                raw = "${EVENT_NOTIF_SENTINEL}$start",
                nowMs = now,
                viewerTz = utc,
            )
        )
    }
}
