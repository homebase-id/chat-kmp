package id.homebase.core.location.tracking

import co.touchlab.kermit.Logger
import id.homebase.api.coroutines.supervisedScope
import kotlin.time.Clock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    // ponytail: temporary background-stall instrumentation. auth codes:
    // 0=NotDetermined 1=Restricted 2=Denied 3=AuthorizedAlways 4=AuthorizedWhenInUse
    private var lastAuth: Int = -1
    private var lastFixMs: Long = 0L
    private var heartbeatJob: Job? = null

    private val delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
        override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
            val points = didUpdateLocations
                .filterIsInstance<CLLocation>()
                .map { it.toRawPoint(fg = isForegroundProfile(profile)) }
            if (points.isEmpty()) return
            lastFixMs = nowMs()
            logger.i { "fix n=${points.size} fg=${isForegroundProfile(profile)} t=${points.last().t}" }
            scope.launch { sink.submit(points) }
        }

        override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
            logger.w { "didFailWithError: ${didFailWithError.localizedDescription}" }
        }

        // ponytail: temporary instrumentation to prove the background-stall root cause.
        // If we see "iOS PAUSED updates" fire when backgrounded+stationary, the culprit
        // is pausesLocationUpdatesAutomatically=true. Remove once confirmed.
        override fun locationManagerDidPauseLocationUpdates(manager: CLLocationManager) {
            logger.w { "iOS PAUSED updates (profile=$profile, auth=${manager.authorizationStatus})" }
        }

        override fun locationManagerDidResumeLocationUpdates(manager: CLLocationManager) {
            logger.i { "iOS RESUMED updates (profile=$profile)" }
        }

        override fun locationManagerDidChangeAuthorization(manager: CLLocationManager) {
            lastAuth = manager.authorizationStatus
            logger.i { "AUTH changed -> $lastAuth (3=Always 4=WhenInUse)" }
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

    override fun start(profile: TrackingProfile) {
        this.profile = profile
        applyProfile(profile)
        if (started) return
        manager.startMonitoringSignificantLocationChanges()
        manager.startUpdatingLocation()
        started = true
        lastAuth = manager.authorizationStatus
        startHeartbeat()
        logger.i {
            "Started profile=$profile auth=$lastAuth (3=Always 4=WhenInUse) " +
                "allowsBackground=${manager.allowsBackgroundLocationUpdates} " +
                "pausesAuto=${manager.pausesLocationUpdatesAutomatically} " +
                "distanceFilter=${manager.distanceFilter}"
        }
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
        heartbeatJob?.cancel()
        heartbeatJob = null
        logger.i { "Stopped" }
    }

    private fun applyProfile(profile: TrackingProfile) {
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

    // ponytail: heartbeat separates suspend from starve. While tracking it logs every 15s — a GAP
    // in these timestamps means iOS suspended the whole app; heartbeats still flowing while
    // lastFixAgeMs climbs means the app is alive but the GPS radio stopped delivering (pause/starve).
    private fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(HEARTBEAT_MS)
                val age = if (lastFixMs == 0L) -1 else nowMs() - lastFixMs
                logger.i { "HEARTBEAT profile=$profile auth=$lastAuth lastFixAgeMs=$age started=$started" }
            }
        }
    }

    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

    private companion object {
        const val HEARTBEAT_MS = 15_000L
    }
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
