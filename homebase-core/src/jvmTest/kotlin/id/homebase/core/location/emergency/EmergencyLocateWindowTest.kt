package id.homebase.core.location.emergency

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the how-far-back floor rule for the emergency locate panel: options never dip
 * below the peer's last-data-point age + 1h, cap 96h, degenerate cases covered.
 */
class EmergencyLocateWindowTest {

    private val hourMs = 3_600_000L

    @Test
    fun freshData_offersAllPresets() {
        // 30 minutes old → floor 1h → everything from 6h up.
        assertEquals(
            listOf(6, 12, 24, 48, 72, 96),
            locateWindowOptionsHours(30 * 60_000L),
        )
    }

    @Test
    fun nullAge_noDataYet_offersAllPresets() {
        assertEquals(LOCATE_WINDOW_PRESETS_HOURS, locateWindowOptionsHours(null))
    }

    @Test
    fun floorIsAgePlusOneHour() {
        // 5h old → floor 6h → 6h itself still allowed.
        assertEquals(listOf(6, 12, 24, 48, 72, 96), locateWindowOptionsHours(5 * hourMs))
        // 6h old → floor 7h → 6h drops off.
        assertEquals(listOf(12, 24, 48, 72, 96), locateWindowOptionsHours(6 * hourMs))
        // 23h old → floor 24h → 24h allowed.
        assertEquals(listOf(24, 48, 72, 96), locateWindowOptionsHours(23 * hourMs))
        // 47.5h old → floor 48h (floor rounds the hour down, +1 covers the partial hour).
        assertEquals(listOf(48, 72, 96), locateWindowOptionsHours(47 * hourMs + 30 * 60_000L))
    }

    @Test
    fun cappedAt96Hours() {
        // 90h old → floor 91h → only the 96h preset survives.
        assertEquals(listOf(96), locateWindowOptionsHours(90 * hourMs))
    }

    @Test
    fun olderThanCap_stillOffersMaxOption() {
        // Newest point older than 4 days: every preset is below the floor — offer the max
        // anyway (the fetch may legitimately return nothing; the server clamps regardless).
        assertEquals(listOf(96), locateWindowOptionsHours(200 * hourMs))
    }
}
