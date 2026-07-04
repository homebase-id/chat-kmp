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
import platform.CoreLocation.kCLLocationAccuracyNearestTenMeters
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
    private var profile = TrackingProfile.HistoryBackground

    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val points = didUpdateLocations
                .filterIsInstance<CLLocation>()
                .map { it.toRawPoint(fg = isForegroundProfile(profile)) }
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
            // Battery-friendly default; applyProfile() flips this to false while live-sharing.
            pausesLocationUpdatesAutomatically = true
            activityType = CLActivityTypeOther
        }
    }

    override fun start(profile: TrackingProfile) {
        this.profile = profile
        applyProfile(profile)
        if (started) return
        manager.startMonitoringSignificantLocationChanges()
        manager.startUpdatingLocation()
        started = true
        logger.i { "Started (profile=$profile)" }
    }

    override fun setProfile(profile: TrackingProfile) {
        if (this.profile == profile) return
        this.profile = profile
        if (!started) return
        applyProfile(profile)
        logger.i { "Profile -> $profile" }
    }

    override fun stop() {
        manager.stopUpdatingLocation()
        manager.stopMonitoringSignificantLocationChanges()
        started = false
        logger.i { "Stopped" }
    }

    private fun applyProfile(profile: TrackingProfile) {
        // Keep the app alive and streaming in the background ONLY while live-sharing. Leaving
        // pausesLocationUpdatesAutomatically on lets iOS pause updates when it deems the device
        // stationary, which drops the active-location assertion so the backgrounded app is
        // suspended/killed within minutes — it then only revives on a coarse significant-location
        // change, making a live share appear to update "only in the foreground". Passive history
        // keeps auto-pause on to save battery (a stationary user simply records fewer points).
        val liveSharing = profile == TrackingProfile.LiveForeground ||
            profile == TrackingProfile.LiveBackground
        manager.pausesLocationUpdatesAutomatically = !liveSharing
        when (profile) {
            // Live (share / live-map view): best accuracy, tight filter — fresh fixes matter.
            TrackingProfile.LiveForeground -> {
                manager.desiredAccuracy = kCLLocationAccuracyBest
                manager.distanceFilter = 10.0
            }
            // History-only foreground: balanced — no live consumer needs best accuracy (#846).
            TrackingProfile.HistoryForeground -> {
                manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
                manager.distanceFilter = 25.0
            }
            // Background (live or history): low power, OS-throttled — unchanged.
            TrackingProfile.LiveBackground, TrackingProfile.HistoryBackground -> {
                manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
                manager.distanceFilter = 50.0
            }
        }
    }

    private fun isForegroundProfile(profile: TrackingProfile): Boolean =
        profile == TrackingProfile.LiveForeground || profile == TrackingProfile.HistoryForeground
}

@OptIn(ExperimentalForeignApi::class)
internal fun CLLocation.toRawPoint(fg: Boolean): RawLocationPoint {
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
