package id.homebase.core.location.tracking

/**
 * Acquisition profile for the platform tracker — chosen by [LocationTrackingCoordinator] from BOTH
 * the app's foreground state AND *why* GPS is running (resolved by [GpsDemand.resolveProfile]). A
 * **live** consumer (an active share or the live-map view) wants frequent, fresh, high-accuracy
 * fixes; **history**-only wants battery-friendly, displacement-based sampling.
 *
 * Today only the two FOREGROUND profiles differ in their parameters; both background profiles map to
 * the same OS-throttled low-power cadence — the background / cold-wake path is intentionally left
 * untouched (changing it is higher risk, lower value, and OS-capped anyway). The per-profile tuning
 * values live in one common table, [TrackingProfile.spec].
 */
enum class TrackingProfile {
    /** App foreground + a live consumer: high accuracy, tight interval/displacement. */
    LiveForeground,

    /** App background + a live consumer: low power, batched/OS-throttled. */
    LiveBackground,

    /** App foreground, history only: balanced power, larger displacement (battery-friendly). */
    HistoryForeground,

    /** App background, history only (and the default at cold start): low power, batched. */
    HistoryBackground,
}

/**
 * One captured GPS fix, platform-agnostic. Optional fields are null when the
 * platform didn't report them.
 */
data class RawLocationPoint(
    /** Capture time, epoch ms UTC. */
    val t: Long,
    val lat: Double,
    val lon: Double,
    /** Horizontal accuracy radius in meters. */
    val acc: Double?,
    /** Altitude in meters. */
    val alt: Double? = null,
    /** Vertical accuracy in meters. */
    val altAcc: Double? = null,
    /** Speed in m/s. */
    val spd: Double? = null,
    /** Heading/bearing in degrees. */
    val hdg: Double? = null,
    /** Capture source, one of [LocationSources]: gps | net | fused | slc (significant-location-change). */
    val src: String,
    /** True when captured in foreground precise mode. */
    val fg: Boolean,
    /** Pedometer steps since the previous accepted point (delta); set by the store. */
    val steps: Int? = null,
    /** Battery %, 0..100; set by the store. */
    val bat: Int? = null,
)

/**
 * Where captured points land. Implemented by `LocationPointStore` (buffered into
 * the local DB); a plain interface so platform actuals — including the Android
 * BroadcastReceiver woken in a cold process — can submit without referencing the
 * store type.
 */
interface LocationPointSink {
    suspend fun submit(points: List<RawLocationPoint>)
}

/**
 * Platform GPS tracker. Implementations register OS location callbacks and feed
 * captured fixes into the [LocationPointSink] they were created with; they hold
 * no business logic and never touch the network.
 *
 * Battery contract (see plan): Android uses FusedLocationProvider — a
 * balanced-power batched PendingIntent registration that survives the app
 * process, plus a high-accuracy callback overlay only while a foreground profile is active;
 * no foreground service. iOS uses CLLocationManager with auto-pause and
 * significant-location-change as the relaunch vector.
 */
interface LocationTracker {
    /** False on desktop/web — drives the "tracking unavailable on this device" UI. */
    val isAvailable: Boolean

    /**
     * Idempotent. Registers OS location updates for [profile].
     * On platforms with [isAvailable] = false this is a no-op.
     */
    fun start(profile: TrackingProfile)

    /** Switch the acquisition profile while running (foreground/background or live/history change). */
    fun setProfile(profile: TrackingProfile)

    /** Unregister all OS location updates. */
    fun stop()
}

expect fun createLocationTracker(sink: LocationPointSink): LocationTracker
