package id.homebase.chat.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * Pins the Google Calendar "add to calendar" URL format. Google parses
 * `dates=` as compact UTC and uses `ctz=` to pre-select the zone in the
 * create-event form — so the URL must carry both the exact UTC instant
 * and the author's IANA zone.
 */
class GoogleCalendarUrlTest {

    @Test
    fun tokyo_event_url_carries_utc_dates_and_ctz_in_authors_zone() {
        val startMs = LocalDateTime(2026, 5, 12, 9, 0)
            .toInstant(TimeZone.of("Asia/Tokyo"))
            .toEpochMilliseconds()
        val descriptor = EventDescriptor(
            title = "Team Standup",
            description = "weekly sync",
            startUtcMs = startMs,
            endUtcMs = startMs + 60 * 60_000L,
            timezone = "Asia/Tokyo",
            locationText = "Shibuya HQ",
        )

        val url = googleCalendarUrl(descriptor)

        assertTrue(url.startsWith("https://calendar.google.com/calendar/render?"), "endpoint wrong: $url")
        // 09:00 JST == 00:00 UTC; +1h == 01:00 UTC. The "/" between start and end
        // is URL-encoded to %2F by urlEncode (it doesn't whitelist slash).
        assertTrue("dates=20260512T000000Z%2F20260512T010000Z" in url, "UTC dates wrong: $url")
        assertTrue("ctz=Asia%2FTokyo" in url, "ctz must be the author's IANA zone (URL-encoded): $url")
        assertTrue("text=Team+Standup" in url, "spaces in title must encode as '+': $url")
        assertTrue("details=weekly+sync" in url)
        assertTrue("location=Shibuya+HQ" in url)
    }

    @Test
    fun missing_end_defaults_to_start_plus_one_hour() {
        val startMs = LocalDateTime(2026, 5, 12, 9, 0)
            .toInstant(TimeZone.UTC)
            .toEpochMilliseconds()
        val url = googleCalendarUrl(
            EventDescriptor(
                title = "x",
                description = "",
                startUtcMs = startMs,
                endUtcMs = null,
                timezone = "UTC",
            )
        )
        assertTrue("dates=20260512T090000Z%2F20260512T100000Z" in url, "1h default wrong: $url")
    }

    @Test
    fun special_chars_in_title_are_percent_encoded() {
        val url = googleCalendarUrl(
            EventDescriptor(
                title = "Q&A: TZ test (½h)",
                description = "",
                startUtcMs = 0L,
                timezone = "UTC",
            )
        )
        // & and : and ( ) and ½ must not appear unencoded as URL syntax chars.
        val textParam = url.substringAfter("text=").substringBefore("&")
        assertEquals("Q%26A%3A+TZ+test+%28%C2%BDh%29", textParam, "title encoding wrong: $textParam")
    }
}
