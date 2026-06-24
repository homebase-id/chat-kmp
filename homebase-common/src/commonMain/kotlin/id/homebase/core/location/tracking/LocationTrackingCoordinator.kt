package id.homebase.core.location.tracking

import co.touchlab.kermit.Logger
import id.homebase.core.location.LocationPreferences
import id.homebase.core.permissions.isLocationPermissionGranted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lifecycle brain of the Location tracker — owns the start/stop/mode decisions
 * so the platform trackers stay dumb. This is the SINGLE owner of the tracker:
 * nothing else calls [tracker] start/stop/setMode.
 *
 * **Why GPS runs (the acquire gate).** GPS runs when [GpsDemand.wants] is true — i.e. the user
 * allows location **history** ([LocationPreferences.allowLocationHistory]), OR a live **share** is
 * active ([liveShareActive]), OR a **transient** consumer holds a demand ([acquireTransientDemand],
 * e.g. the live map wanting the user's own dot). The transient demand is what lets a screen show the
 * user's position without enabling history — the acquire gate is intentionally broader than the
 * routing gate (history persist iff `allowLocationHistory`, in [LocationFixRouter]).
 *
 * **Permission.** Starting the OS tracker without location permission throws (FusedLocation /
 * CLLocationManager), so [canRunGps] gates every start on `isLocationPermissionGranted()`. A
 * transient demand can be acquired before the grant (the live map opens, then the user grants); the
 * next [refreshGpsHold] re-arms once permission lands.
 *
 * - [onProcessStart] re-arms on every process start when GPS is wanted+permitted. Hooked from
 *   MainApplication.onCreate (Android — also boot-receiver / background wakes) and initializeApp
 *   (iOS — also the significant-location-change relaunch), so a live share survives a full process
 *   kill without the UI. It deliberately does NOT hang off onPostAuthenticated (never runs on
 *   headless cold starts).
 * - App foreground transitions switch the capture profile and run a periodic foreground flush
 *   ticker; backgrounding stops the ticker (background flushes piggyback on batch deliveries).
 * - [onFlushDue] / [liveShareActive] are injected by DI (the uploader and the share service live in
 *   higher modules this one cannot reference). They must be cheap.
 */
class LocationTrackingCoordinator(
    private val preferences: LocationPreferences,
    private val tracker: LocationTracker,
    private val scope: CoroutineScope,
) {
    private val logger = Logger.withTag("LocationTrackingCoordinator")

    /** Wired in AppModule to LocationTrackUploaderService.flushIfDue(). */
    var onFlushDue: suspend () -> Unit = {}

    /**
     * Wired in AppModule to LiveLocationShareService.hasLiveShare(). When true the coordinator keeps
     * GPS running even if the history switch is off, so a live share survives — including across a
     * process kill, via [onProcessStart]. Default false (no hold).
     */
    var liveShareActive: () -> Boolean = { false }

    /** Transient GPS demands (live map open, one-shot fix, …). See [acquireTransientDemand]. */
    private val demand = GpsDemand()

    private var processStarted = false
    private var isForeground = false
    private var tickerJob: Job? = null

    // Tracks whether we've armed the tracker, so [applyGpsHold] logs only on transitions (not on
    // every idempotent re-evaluation).
    private var gpsRunning = false

    /** GPS should run when history is allowed, a live share needs it, OR a transient hold is held. */
    private fun wantsGps(): Boolean =
        demand.wants(preferences.allowLocationHistory.value, liveShareActive())

    /** GPS can only physically run with hardware present AND location permission granted. */
    private fun canRunGps(): Boolean = tracker.isAvailable && isLocationPermissionGranted()

    /**
     * Start or stop the tracker to match [wantsGps] (gated by [canRunGps]). The single start/stop
     * decision point — idempotent, [tracker] start/stop tolerate repeats.
     */
    private fun applyGpsHold() {
        if (!tracker.isAvailable) return
        val wants = wantsGps()
        if (wants && canRunGps()) {
            tracker.start(if (isForeground) TrackingMode.Foreground else TrackingMode.Background)
            if (isForeground) startTicker()
            if (!gpsRunning) {
                gpsRunning = true
                logger.i {
                    "GPS armed (mode=${if (isForeground) "FG" else "BG"} " +
                        "allowHistory=${preferences.allowLocationHistory.value} " +
                        "liveShare=${liveShareActive()} transient=${demand.hasTransient()})"
                }
            }
        } else {
            tracker.stop()
            stopTicker()
            if (gpsRunning) {
                gpsRunning = false
                logger.i { "GPS stopped (nothing wants it, or not permitted)" }
            }
            // Key diagnostic for "I'm on the live map but my dot never appears": something wants GPS
            // (e.g. the live-map transient hold) but it can't run because permission isn't granted.
            if (wants && !isLocationPermissionGranted()) {
                logger.i {
                    "GPS wanted but NOT started — location permission not granted " +
                        "(transient=${demand.hasTransient()} liveShare=${liveShareActive()})"
                }
            }
        }
    }

    /**
     * Acquire a transient GPS hold so the hardware runs while a foreground consumer needs the user's
     * own position (e.g. the live map), independent of the history switch. Release the returned token
     * when done (the live map does so in `onCleared`). Safe to acquire before permission is granted —
     * GPS simply stays off until permission lands and the hold is re-evaluated.
     */
    fun acquireTransientDemand(reason: DemandReason): DemandToken {
        val id = demand.acquire(reason)
        logger.i { "GPS demand acquired: $reason (active=${demand.activeReasons()})" }
        refreshGpsHold()
        return DemandToken {
            demand.release(id)
            logger.i { "GPS demand released: $reason (active=${demand.activeReasons()})" }
            refreshGpsHold()
        }
    }

    fun onProcessStart() {
        if (processStarted || !tracker.isAvailable) return
        processStarted = true

        applyGpsHold()
        logger.i {
            "Process start: GPS hold applied (allowHistory=${preferences.allowLocationHistory.value} " +
                "liveShare=${liveShareActive()} transient=${demand.hasTransient()} permitted=${isLocationPermissionGranted()})"
        }

        observeAppForeground { foreground ->
            isForeground = foreground
            if (wantsGps() && canRunGps()) {
                tracker.setMode(if (foreground) TrackingMode.Foreground else TrackingMode.Background)
            }
            if (foreground) startTicker() else stopTicker()
        }
    }

    /**
     * Re-evaluate the GPS hold after a live share / transient demand / permission change. Starts or
     * stops the tracker to match [wantsGps]. No-op until [onProcessStart] has run (which itself
     * evaluates the hold).
     */
    fun refreshGpsHold() {
        if (!processStarted) return
        applyGpsHold()
    }

    suspend fun setAllowLocationHistory(enabled: Boolean) {
        preferences.setAllowLocationHistory(enabled)
        // Re-evaluate the hold: enabling starts GPS (if permitted); disabling stops it unless a
        // share or transient demand still wants it.
        applyGpsHold()
        if (!enabled) {
            // Final drain so the last points don't sit in the buffer until history is next enabled.
            scope.launch { onFlushDue() }
        }
        logger.i { "Location history ${if (enabled) "enabled" else "disabled"}" }
    }

    /**
     * Re-evaluate after a logout/login wipe. The pref StateFlow was reset by
     * LocationPreferences.reset() before this runs; if nothing wants GPS, stop capturing.
     */
    fun reset() {
        if (!tracker.isAvailable) return
        if (!wantsGps()) {
            tracker.stop()
            stopTicker()
        }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            // Immediate drain on entering the foreground, then periodic.
            onFlushDue()
            while (isActive) {
                delay(FOREGROUND_FLUSH_INTERVAL_MS)
                onFlushDue()
            }
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    private companion object {
        const val FOREGROUND_FLUSH_INTERVAL_MS = 120_000L
    }
}
