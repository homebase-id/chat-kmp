package id.homebase.core.location.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drift guard for #978: pins the per-profile tuning table and classifications both platform
 * trackers translate. A change here is a deliberate tuning decision, not a refactor.
 */
class TrackingProfileSpecTest {

    @Test
    fun liveForegroundSpec() = assertEquals(
        TrackingProfileSpec(TrackingAccuracy.Precise, minIntervalMs = 15_000L, minDisplacementM = 10.0),
        TrackingProfile.LiveForeground.spec,
    )

    @Test
    fun historyForegroundSpec() = assertEquals(
        TrackingProfileSpec(TrackingAccuracy.Balanced, minIntervalMs = 30_000L, minDisplacementM = 25.0),
        TrackingProfile.HistoryForeground.spec,
    )

    @Test
    fun backgroundProfilesCollapseToOneCoarseSpec() {
        val expected = TrackingProfileSpec(TrackingAccuracy.Coarse, minIntervalMs = null, minDisplacementM = 50.0)
        assertEquals(expected, TrackingProfile.LiveBackground.spec)
        assertEquals(expected, TrackingProfile.HistoryBackground.spec)
    }

    @Test
    fun androidBackgroundBaseline() {
        assertEquals(TrackingAccuracy.Balanced, AndroidBackgroundBaseline.ACCURACY)
        assertEquals(60_000L, AndroidBackgroundBaseline.INTERVAL_MS)
        assertEquals(600_000L, AndroidBackgroundBaseline.MAX_DELAY_MS)
        assertEquals(25.0, AndroidBackgroundBaseline.MIN_DISPLACEMENT_M)
    }

    @Test
    fun classifications() {
        assertTrue(TrackingProfile.LiveForeground.isForeground)
        assertTrue(TrackingProfile.LiveForeground.isLive)
        assertTrue(TrackingProfile.HistoryForeground.isForeground)
        assertFalse(TrackingProfile.HistoryForeground.isLive)
        assertFalse(TrackingProfile.LiveBackground.isForeground)
        assertTrue(TrackingProfile.LiveBackground.isLive)
        assertFalse(TrackingProfile.HistoryBackground.isForeground)
        assertFalse(TrackingProfile.HistoryBackground.isLive)
    }

    @Test
    fun sourceVocabulary() {
        assertEquals("gps", LocationSources.GPS)
        assertEquals("net", LocationSources.NET)
        assertEquals("fused", LocationSources.FUSED)
        assertEquals("slc", LocationSources.SLC)
    }
}
