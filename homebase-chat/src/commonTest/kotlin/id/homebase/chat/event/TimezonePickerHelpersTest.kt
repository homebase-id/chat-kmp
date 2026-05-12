package id.homebase.chat.event

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

class TimezonePickerHelpersTest {

    // May 15, noon UTC — gives:
    //   Europe/Copenhagen → CEST → GMT+2
    //   America/Los_Angeles → PDT  → GMT-7
    //   Asia/Kolkata        → IST  → GMT+5:30
    private val mayInstant =
        LocalDateTime(2026, 5, 15, 12, 0).toInstant(TimeZone.UTC)

    // Jan 15, noon UTC — gives:
    //   Europe/Copenhagen → CET → GMT+1
    //   America/Los_Angeles → PST → GMT-8
    private val janInstant =
        LocalDateTime(2026, 1, 15, 12, 0).toInstant(TimeZone.UTC)

    @Test
    fun friendly_label_copenhagen_summer() {
        assertEquals("Copenhagen (GMT+2)", friendlyZoneLabel("Europe/Copenhagen", mayInstant))
    }

    @Test
    fun friendly_label_kolkata_half_hour_offset() {
        // Pins the half-hour offset path — easy to break with a "minutes / 60" refactor.
        assertEquals("Kolkata (GMT+5:30)", friendlyZoneLabel("Asia/Kolkata", mayInstant))
    }

    @Test
    fun friendly_label_los_angeles_winter() {
        assertEquals("Los Angeles (GMT-8)", friendlyZoneLabel("America/Los_Angeles", janInstant))
    }

    @Test
    fun friendly_label_los_angeles_summer() {
        assertEquals("Los Angeles (GMT-7)", friendlyZoneLabel("America/Los_Angeles", mayInstant))
    }

    @Test
    fun friendly_label_utc_special_case() {
        // No slash — passes through whole and gets GMT+0.
        assertEquals("UTC (GMT+0)", friendlyZoneLabel("UTC", mayInstant))
    }

    @Test
    fun matches_query_by_city_prefix() {
        assertTrue(matchesQuery("Asia/Kolkata", "kol", mayInstant))
    }

    @Test
    fun matches_query_by_offset_string() {
        // Offset substring should land — lets users type "+5:30" to find IST.
        assertTrue(matchesQuery("Asia/Kolkata", "+5:30", mayInstant))
    }

    @Test
    fun matches_query_rejects_unrelated_continent() {
        assertFalse(matchesQuery("Europe/Berlin", "asia", mayInstant))
    }
}
