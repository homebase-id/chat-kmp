package id.homebase.core.location.tracking

/** App is in the foreground — the platform tracker runs its precise overlay/accuracy. */
val TrackingProfile.isForeground: Boolean
    get() = this == TrackingProfile.LiveForeground || this == TrackingProfile.HistoryForeground

/** A live consumer (active share or the live-map view) is driving GPS. */
val TrackingProfile.isLive: Boolean
    get() = this == TrackingProfile.LiveForeground || this == TrackingProfile.LiveBackground

/** Platform-agnostic accuracy tier; actuals translate to a Fused `Priority` / `kCLLocationAccuracy*`. */
enum class TrackingAccuracy { Precise, Balanced, Coarse }

/**
 * Per-profile tuning values. [minIntervalMs] is null on the background spec: background cadence is
 * OS-throttled (Android registers no foreground overlay there — only [AndroidBackgroundBaseline];
 * iOS is distance-filtered only, so [minIntervalMs] is Android-only either way).
 */
data class TrackingProfileSpec(
    val accuracy: TrackingAccuracy,
    val minIntervalMs: Long?,
    val minDisplacementM: Double,
)

/**
 * The one per-profile tuning table both platform trackers translate, hoisted here so the values
 * can't drift between Android and iOS (#978; same seam philosophy as
 * [OneShotLocationProvider.getCurrentFix]).
 */
val TrackingProfile.spec: TrackingProfileSpec
    get() = when (this) {
        // Live: high accuracy, tight interval/displacement — fresh fixes matter.
        TrackingProfile.LiveForeground ->
            TrackingProfileSpec(TrackingAccuracy.Precise, minIntervalMs = 15_000L, minDisplacementM = 10.0)
        // History-only: balanced power, larger displacement (#846).
        TrackingProfile.HistoryForeground ->
            TrackingProfileSpec(TrackingAccuracy.Balanced, minIntervalMs = 30_000L, minDisplacementM = 25.0)
        // Both background profiles intentionally collapse to one low-power, OS-throttled spec
        // (see [TrackingProfile] — the background / cold-wake path is deliberately untouched).
        TrackingProfile.LiveBackground, TrackingProfile.HistoryBackground ->
            TrackingProfileSpec(TrackingAccuracy.Coarse, minIntervalMs = null, minDisplacementM = 50.0)
    }

/**
 * Android background baseline — the always-on batched PendingIntent registration that works
 * without a foreground service. Part of the same policy as [TrackingProfile.spec].
 */
object AndroidBackgroundBaseline {
    val ACCURACY = TrackingAccuracy.Balanced
    const val INTERVAL_MS = 60_000L
    const val MAX_DELAY_MS = 600_000L
    const val MIN_DISPLACEMENT_M = 25.0
}

/** Canonical [RawLocationPoint.src] vocabulary. */
object LocationSources {
    const val GPS = "gps"
    const val NET = "net"
    const val FUSED = "fused"

    /** Significant-location-change (iOS relaunch vector). */
    const val SLC = "slc"
}
