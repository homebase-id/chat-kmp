package id.homebase.core.location.tracking

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Pure truth-table + refcount tests for the GPS acquire decision. DB-free → runs on all targets. */
class GpsDemandTest {

    @Test
    fun wantsWhenHistoryAllowed() {
        assertTrue(GpsDemand().wants(allowHistory = true, liveShare = false))
    }

    @Test
    fun wantsWhenLiveShareActive() {
        assertTrue(GpsDemand().wants(allowHistory = false, liveShare = true))
    }

    @Test
    fun doesNotWantWhenNothingNeedsIt() {
        assertFalse(GpsDemand().wants(allowHistory = false, liveShare = false))
    }

    @Test
    fun transientHoldAloneMakesWantsTrue() {
        val demand = GpsDemand()
        val token = demand.acquire(DemandReason.LiveMapOpen)
        assertTrue(demand.hasTransient())
        // The whole point of #841: a transient hold runs GPS WITHOUT history or a share.
        assertTrue(demand.wants(allowHistory = false, liveShare = false))
        demand.release(token)
        assertFalse(demand.hasTransient())
        assertFalse(demand.wants(allowHistory = false, liveShare = false))
    }

    @Test
    fun refcountsMultipleHolds() {
        val demand = GpsDemand()
        val a = demand.acquire(DemandReason.LiveMapOpen)
        val b = demand.acquire(DemandReason.LiveMapOpen)
        demand.release(a)
        assertTrue(demand.hasTransient()) // b still held
        demand.release(b)
        assertFalse(demand.hasTransient())
    }

    @Test
    fun releasingUnknownTokenIsNoOp() {
        val demand = GpsDemand()
        val token = demand.acquire(DemandReason.LiveMapOpen)
        demand.release(token + 999) // unknown id
        assertTrue(demand.hasTransient())
        demand.release(token)
        assertFalse(demand.hasTransient())
    }

    @Test
    fun activeReasonsReflectsHeldHolds() {
        val demand = GpsDemand()
        demand.acquire(DemandReason.LiveMapOpen)
        assertEquals(listOf(DemandReason.LiveMapOpen), demand.activeReasons().toList())
    }

    // ── resolveProfile (#846): live (share OR transient hold) beats history; crossed with FG/BG ──

    @Test
    fun profileLiveForegroundWhenSharingInForeground() {
        assertEquals(
            TrackingProfile.LiveForeground,
            GpsDemand().resolveProfile(isForeground = true, allowHistory = false, liveShare = true),
        )
    }

    @Test
    fun profileLiveBackgroundWhenSharingInBackground() {
        assertEquals(
            TrackingProfile.LiveBackground,
            GpsDemand().resolveProfile(isForeground = false, allowHistory = true, liveShare = true),
        )
    }

    @Test
    fun profileHistoryForegroundWhenOnlyHistoryInForeground() {
        assertEquals(
            TrackingProfile.HistoryForeground,
            GpsDemand().resolveProfile(isForeground = true, allowHistory = true, liveShare = false),
        )
    }

    @Test
    fun profileHistoryBackgroundWhenOnlyHistoryInBackground() {
        assertEquals(
            TrackingProfile.HistoryBackground,
            GpsDemand().resolveProfile(isForeground = false, allowHistory = true, liveShare = false),
        )
    }

    @Test
    fun transientHoldCountsAsLiveForProfile() {
        // The live-map view holds GPS via a transient demand with history off and no share — it still
        // wants the high-accuracy Live profile, not the battery-saving History one.
        val demand = GpsDemand()
        demand.acquire(DemandReason.LiveMapOpen)
        assertEquals(
            TrackingProfile.LiveForeground,
            demand.resolveProfile(isForeground = true, allowHistory = false, liveShare = false),
        )
    }
}
