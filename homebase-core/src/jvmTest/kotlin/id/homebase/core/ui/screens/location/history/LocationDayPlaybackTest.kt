package id.homebase.core.ui.screens.location.history

import id.homebase.api.sync.database.BufferedLocationPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class LocationDayPlaybackTest {

    private val dev = Uuid.parse("00000000-0000-0000-0000-000000000001")

    private fun pt(t: Long, lat: Double = 52.0, lon: Double = 13.0) =
        BufferedLocationPoint(t = t, lat = lat, lon = lon, acc = 10.0, src = "gps", fg = true)

    private fun trace(vararg segments: List<BufferedLocationPoint>) =
        DeviceTrace(deviceId = dev, segments = segments.toList())

    private val min = 60_000L

    // ── Dwell detection ──

    @Test
    fun dwell_detected_for_stay_of_at_least_15_min() {
        // Four fixes at one spot spanning 16 minutes.
        val seg = listOf(pt(0), pt(5 * min), pt(10 * min), pt(16 * min))
        val pb = DayPlayback.build(listOf(trace(seg)))
        assertNotNull(pb)
        assertEquals(1, pb.stops.size)
        val stop = pb.stops.single()
        assertEquals(16 * min, stop.durationMs)
        assertTrue(kotlin.math.abs(stop.lat - 52.0) < 1e-6)
        assertTrue(kotlin.math.abs(stop.lon - 13.0) < 1e-6)
    }

    @Test
    fun no_dwell_for_stay_under_15_min() {
        val seg = listOf(pt(0), pt(5 * min), pt(10 * min))
        val pb = DayPlayback.build(listOf(trace(seg)))
        assertNotNull(pb)
        assertTrue(pb.stops.isEmpty())
    }

    @Test
    fun dwell_spanning_a_tracking_gap_still_counts() {
        // Same spot, but split into two segments by a tracking gap (20 min apart).
        val pb = DayPlayback.build(listOf(trace(listOf(pt(0)), listOf(pt(20 * min)))))
        assertNotNull(pb)
        assertEquals(1, pb.stops.size)
        assertEquals(20 * min, pb.stops.single().durationMs)
    }

    @Test
    fun empty_day_has_no_playback() {
        assertNull(DayPlayback.build(emptyList()))
        assertNull(DayPlayback.build(listOf(trace(emptyList()))))
    }

    // ── Warp ──

    /** Leg A (moving 5 min) → 8 h parked → leg B (moving 5 min), one continuous segment. */
    private fun warpDay(): DayPlayback {
        val seg = buildList {
            // Leg A: 5 fixes, ~111 m apart, 1 min steps.
            for (i in 0..4) add(pt(i * min, lat = 52.000 + 0.001 * i, lon = 13.0))
            // Parked: same spot as A's end, every 30 min for 8 h.
            val parkLat = 52.004
            for (i in 1..16) add(pt(4 * min + i * 30 * min, lat = parkLat, lon = 13.0))
            // Leg B: resume moving from the parked spot.
            val bStart = 4 * min + 16 * 30 * min
            for (i in 1..5) add(pt(bStart + i * min, lat = 52.004 + 0.001 * i, lon = 13.0))
        }
        return DayPlayback.build(listOf(trace(seg)))!!
    }

    @Test
    fun warp_endpoints_and_bounds() {
        val pb = warpDay()
        assertEquals(pb.firstFixMs, pb.clockAtProgress(0f))
        assertEquals(pb.lastFixMs, pb.clockAtProgress(1f))
        assertTrue(pb.totalAnimMillis in 5_000..18_000)
    }

    @Test
    fun warp_is_monotonic() {
        val pb = warpDay()
        var prev = pb.clockAtProgress(0f)
        var p = 0.05f
        while (p <= 1f) {
            val c = pb.clockAtProgress(p)
            assertTrue(c >= prev, "clock went backwards at p=$p ($c < $prev)")
            prev = c
            p += 0.05f
        }
    }

    @Test
    fun idle_is_compressed_so_movement_gets_the_budget() {
        val pb = warpDay()
        val legAEndT = 4 * min               // last moving fix of leg A
        val parkedEndT = 4 * min + 16 * 30 * min // last parked fix (leg B starts after)

        // A quarter through the animation we're still in leg A (the 8 h park hasn't
        // eaten the budget); three-quarters through we're already past it into leg B.
        assertTrue(
            pb.clockAtProgress(0.25f) <= legAEndT,
            "expected to still be in leg A at 25%, was ${pb.clockAtProgress(0.25f)}",
        )
        assertTrue(
            pb.clockAtProgress(0.75f) >= parkedEndT,
            "expected to be past the 8 h park at 75%, was ${pb.clockAtProgress(0.75f)}",
        )
    }

    // ── Dwell-dot size curve ──

    @Test
    fun dwell_radius_curve_is_capped_and_monotonic() {
        val atFloor = dwellRadiusDp(DayPlayback.DWELL_MIN_MS)
        val at30m = dwellRadiusDp(30 * min)
        val at2h = dwellRadiusDp(2 * 60 * min)
        val at4h = dwellRadiusDp(4 * 60 * min)
        val at24h = dwellRadiusDp(24 * 60 * min)

        assertEquals(7f, atFloor, 0.01f)        // MIN_R at the 15-min floor
        assertTrue(at30m in atFloor..at2h)      // monotonic up
        assertTrue(at2h < at4h)
        assertEquals(at4h, at24h, 0.001f)       // saturates by 4 h — 24 h is no bigger
        assertEquals(22f, at24h, 0.01f)         // MAX_R cap
    }
}
