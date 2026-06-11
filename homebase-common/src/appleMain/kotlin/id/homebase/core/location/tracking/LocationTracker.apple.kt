package id.homebase.core.location.tracking

import co.touchlab.kermit.Logger
import id.homebase.api.coroutines.supervisedScope
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.launch
import platform.CoreLocation.CLActivityTypeOther
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.CoreLocation.kCLLocationAccuracyBest
import platform.CoreLocation.kCLLocationAccuracyHundredMeters
import platform.Foundation.NSError
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject

actual fun createLocationTracker(sink: LocationPointSink): LocationTracker =
    AppleLocationTracker(sink)

/**
 * CLLocationManager-based tracker.
 *
 * Battery levers (the ones that demonstrably work — see the Anchor source dive):
 * - `pausesLocationUpdatesAutomatically = true` lets iOS suspend standard
 *   updates while the device is stationary.
 * - Background profile drops to hundred-meter accuracy with a 50m distance
 *   filter; foreground runs best-accuracy at 10m.
 * - Significant-location-change monitoring is always on while tracking — it is
 *   the relaunch vector after the OS terminates the app
 *   (UIApplicationLaunchOptionsLocationKey → initializeApp →
 *   LocationTrackingCoordinator.onProcessStart re-creates this manager inside
 *   the launch runloop).
 */
private class AppleLocationTracker(
    private val sink: LocationPointSink,
) : LocationTracker {

    private val logger = Logger.withTag("AppleLocationTracker")
    private val scope = supervisedScope("AppleLocationTracker")

    override val isAvailable: Boolean = true

    private var started = false
    private var mode = TrackingMode.Background

    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val points = didUpdateLocations
                .filterIsInstance<CLLocation>()
                .map { it.toRawPoint(fg = mode == TrackingMode.Foreground) }
            if (points.isEmpty()) return
            scope.launch { sink.submit(points) }
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            logger.w { "didFailWithError: ${didFailWithError.localizedDescription}" }
        }
    }

    private val manager: CLLocationManager by lazy {
        CLLocationManager().apply {
            delegate = this@AppleLocationTracker.delegate
            allowsBackgroundLocationUpdates = true
            pausesLocationUpdatesAutomatically = true
            activityType = CLActivityTypeOther
        }
    }

    override fun start(mode: TrackingMode) {
        this.mode = mode
        applyProfile(mode)
        if (started) return
        manager.startMonitoringSignificantLocationChanges()
        manager.startUpdatingLocation()
        started = true
        logger.i { "Started (mode=$mode)" }
    }

    override fun setMode(mode: TrackingMode) {
        if (this.mode == mode) return
        this.mode = mode
        if (!started) return
        applyProfile(mode)
        logger.i { "Profile -> $mode" }
    }

    override fun stop() {
        manager.stopUpdatingLocation()
        manager.stopMonitoringSignificantLocationChanges()
        started = false
        logger.i { "Stopped" }
    }

    private fun applyProfile(mode: TrackingMode) {
        when (mode) {
            TrackingMode.Foreground -> {
                manager.desiredAccuracy = kCLLocationAccuracyBest
                manager.distanceFilter = 10.0
            }

            TrackingMode.Background -> {
                manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
                manager.distanceFilter = 50.0
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun CLLocation.toRawPoint(fg: Boolean): RawLocationPoint {
    val (lat, lon) = coordinate.useContents { latitude to longitude }
    return RawLocationPoint(
        t = (timestamp.timeIntervalSince1970 * 1000).toLong(),
        lat = lat,
        lon = lon,
        acc = horizontalAccuracy.takeIf { it >= 0 },
        alt = altitude.takeIf { verticalAccuracy >= 0 },
        altAcc = verticalAccuracy.takeIf { it >= 0 },
        // CL uses negative values as "invalid" sentinels.
        spd = speed.takeIf { it >= 0 },
        hdg = course.takeIf { it >= 0 },
        src = "gps",
        fg = fg,
    )
}
