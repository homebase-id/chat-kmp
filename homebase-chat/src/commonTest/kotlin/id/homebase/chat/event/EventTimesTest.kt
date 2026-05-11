package id.homebase.chat.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class EventTimesTest {

    /**
     * Tokyo author schedules May 12 09:00 JST. A viewer in Los Angeles is
     * still on May 11 17:00 PDT at that instant. The reply chip / bubble
     * should show the viewer's date (May 11), while the "Scheduled: …"
     * tagline carries the author's day-of-week + time (Tue 09:00 Tokyo).
     */
    @Test
    fun cross_zone_event_shows_viewer_local_date_and_carries_author_tagline() {
        val tokyo = TimeZone.of("Asia/Tokyo")
        val la = TimeZone.of("America/Los_Angeles")
        val authored = LocalDateTime(2026, 5, 12, 9, 0)
        val startUtcMs = authored.toInstant(tokyo).toEpochMilliseconds()

        val descriptor = EventDescriptor(
            title = "Standup",
            startUtcMs = startUtcMs,
            timezone = tokyo.id,
        )

        val times = computeEventTimes(descriptor, viewerTz = la)

        assertEquals(LocalDateTime(2026, 5, 11, 17, 0), times.viewerStartLocal)
        assertEquals(la, times.viewerTz)

        assertNotNull(times.authoredStartLocal, "authoredStartLocal must be non-null when zones differ")
        assertEquals(LocalDateTime(2026, 5, 12, 9, 0), times.authoredStartLocal)
        assertEquals(tokyo.id, times.authoredTzId)

        assertNull(times.viewerEndLocal)
        assertNull(times.authoredEndLocal)
    }
}
