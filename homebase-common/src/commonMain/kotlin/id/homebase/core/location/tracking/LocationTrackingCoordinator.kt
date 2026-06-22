package id.homebase.core.location.tracking

import co.touchlab.kermit.Logger
import id.homebase.core.location.LocationPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lifecycle brain of the Location tracker — owns the start/stop/mode decisions
 * so the platform trackers stay dumb.
 *
 * - [onProcessStart] re-arms the tracker on every process start when GPS is
 *   wanted — the master switch is on OR a live-location share is active. Hooked
 *   from MainApplication.onCreate (Android — also covers boot-receiver and
 *   background-location wakes) and initializeApp (iOS — also covers the
 *   UIApplicationLaunchOptionsLocationKey / significant-location-change
 *   relaunch). This is what keeps a live share running reliably across a full
 *   process kill: the relaunch re-arms GPS without needing the UI. It
 *   deliberately does NOT hang off onPostAuthenticated, which never runs on
 *   headless cold starts.
 * - App foreground transitions switch the capture profile and run a periodic
 *   foreground flush ticker; backgrounding stops the ticker (background
 *   flushes piggyback on batch deliveries instead — no alarms/WorkManager).
 * - [onFlushDue] / [liveShareActive] are injected by DI (the uploader and the
 *   live-share service live in higher modules this one cannot reference). They
 *   must be cheap.
 *
 * This is the SINGLE owner of the tracker: nothing else calls [tracker] start/
 * stop/setMode. The live-share feature expresses its need for GPS via
 * [liveShareActive] + [refreshGpsHold] rather than driving the tracker itself,
 * so a share and the master switch can never fight over it, and the coordinator
 * always picks the right Foreground/Background mode.
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
     * GPS running even if the master tracking switch is off, so a live share survives — including
     * across a process kill, via [onProcessStart]. Default false (no hold).
     */
    var liveShareActive: () -> Boolean = { false }

    private var processStarted = false
    private var isForeground = false
    private var tickerJob: Job? = null

    /** GPS should run when the user enabled tracking OR a live share needs it. */
    private fun wantsGps(): Boolean = preferences.trackingEnabled.value || liveShareActive()

    fun onProcessStart() {
        if (processStarted || !tracker.isAvailable) return
        processStarted = true

        if (wantsGps()) {
            tracker.start(TrackingMode.Background)
            logger.i {
                "Process start: GPS re-armed (tracking=${preferences.trackingEnabled.value} " +
                    "liveShare=${liveShareActive()})"
            }
        }

        observeAppForeground { foreground ->
            isForeground = foreground
            if (wantsGps()) {
                tracker.setMode(if (foreground) TrackingMode.Foreground else TrackingMode.Background)
            }
            if (foreground) startTicker() else stopTicker()
        }
    }

    /**
     * Re-evaluate the GPS hold after a live share starts/stops/expires (wired via DI to
     * LiveLocationShareService). Starts or stops the tracker to match [wantsGps]. Idempotent —
     * [tracker] start/stop tolerate repeats. No-op until [onProcessStart] has run (which itself
     * evaluates the hold).
     */
    fun refreshGpsHold() {
        if (!tracker.isAvailable || !processStarted) return
        if (wantsGps()) {
            tracker.start(if (isForeground) TrackingMode.Foreground else TrackingMode.Background)
            if (isForeground) startTicker()
        } else {
            tracker.stop()
            stopTicker()
        }
    }

    suspend fun setTrackingEnabled(enabled: Boolean) {
        preferences.setTrackingEnabled(enabled)
        if (!tracker.isAvailable) return
        if (enabled) {
            tracker.start(if (isForeground) TrackingMode.Foreground else TrackingMode.Background)
            if (isForeground) startTicker()
        } else if (liveShareActive()) {
            // Master switch off but a live share still needs GPS — keep it running (drop to the
            // current-mode cadence), don't stop the tracker.
            tracker.setMode(if (isForeground) TrackingMode.Foreground else TrackingMode.Background)
            scope.launch { onFlushDue() }
        } else {
            tracker.stop()
            stopTicker()
            // Final drain so the last points don't sit in the buffer until the
            // next time tracking is enabled.
            scope.launch { onFlushDue() }
        }
        logger.i { "Tracking ${if (enabled) "enabled" else "disabled"}" }
    }

    /**
     * Re-evaluate after a logout/login wipe. The pref StateFlow was reset by
     * LocationPreferences.reset() before this runs; if neither the master switch
     * nor a live share wants GPS, stop capturing.
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
