package id.homebase.photobase.info

import id.homebase.core.util.offsetLabel
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

// Not in commonTest: the browser has no zone database, so TimeZone.of("Europe/Berlin") throws there.
class PhotoInfoZoneTest {
    @Test
    fun theZoneIsItsOffsetWhenTheMomentWasTaken() {
        val winter = Instant.parse("2026-01-15T12:00:00Z")
        val summer = Instant.parse("2026-07-15T12:00:00Z")

        assertEquals("GMT+0", offsetLabel(winter, TimeZone.UTC))
        assertEquals("GMT-8", offsetLabel(winter, TimeZone.of("America/Los_Angeles")))
        assertEquals("GMT-7", offsetLabel(summer, TimeZone.of("America/Los_Angeles")))
        assertEquals("GMT+5:30", offsetLabel(winter, TimeZone.of("Asia/Kolkata")))
    }

    @Test
    fun aPhotoTakenInTokyoShowsTokyosClockWhereverItsViewed() {
        val at = Instant.parse("2026-01-15T06:00:00Z")
        val newYork = TimeZone.of("America/New_York")

        val shown = shiftedTo(at, UtcOffset(hours = 9), newYork)

        assertEquals(LocalDateTime(2026, 1, 15, 15, 0), shown.toLocalDateTime(newYork))
        assertEquals("GMT+9", offsetLabel(at, UtcOffset(hours = 9).asTimeZone()))
    }

    @Test
    fun theShiftHoldsAcrossTheViewersChangeOfClock() {
        // Berlin goes to summer time at 01:00 UTC on 29 March; 23:30 UTC the night before is 08:30 in Tokyo.
        val berlin = TimeZone.of("Europe/Berlin")
        val shown = shiftedTo(Instant.parse("2026-03-28T23:30:00Z"), UtcOffset(hours = 9), berlin)

        assertEquals(LocalDateTime(2026, 3, 29, 8, 30), shown.toLocalDateTime(berlin))
    }
}
