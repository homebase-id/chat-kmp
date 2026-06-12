package id.homebase.core.location.tracking

/**
 * Capture profile for the platform tracker.
 *
 * - [Foreground] — the app is visible: high accuracy, tight interval/displacement.
 * - [Background] — the app is backgrounded: low power, batched/OS-throttled
 *   delivery. No foreground service on Android; the OS decides the cadence.
 */
enum class TrackingMode { Foreground, Background }

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
    /** Capture source: gps | net | fused | slc (significant-location-change). */
    val src: String,
    /** True when captured in foreground precise mode. */
    val fg: Boolean,
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
 * process, plus a high-accuracy callback overlay only while [TrackingMode.Foreground];
 * no foreground service. iOS uses CLLocationManager with auto-pause and
 * significant-location-change as the relaunch vector.
 */
interface LocationTracker {
    /** False on desktop/web — drives the "tracking unavailable on this device" UI. */
    val isAvailable: Boolean

    /**
     * Idempotent. Registers OS location updates with the profile for [mode].
     * On platforms with [isAvailable] = false this is a no-op.
     */
    fun start(mode: TrackingMode)

    /** Switch capture profile on app foreground/background transitions while running. */
    fun setMode(mode: TrackingMode)

    /** Unregister all OS location updates. */
    fun stop()
}

expect fun createLocationTracker(sink: LocationPointSink): LocationTracker
