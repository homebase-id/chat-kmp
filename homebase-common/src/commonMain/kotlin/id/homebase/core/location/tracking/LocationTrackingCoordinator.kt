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
 * - [onProcessStart] re-arms the tracker on every process start when the
 *   master switch is on. Hooked from MainApplication.onCreate (Android — also
 *   covers boot-receiver and background-location wakes) and initializeApp
 *   (iOS — also covers the UIApplicationLaunchOptionsLocationKey relaunch).
 *   It deliberately does NOT hang off onPostAuthenticated, which never runs on
 *   headless cold starts.
 * - App foreground transitions switch the capture profile and run a periodic
 *   foreground flush ticker; backgrounding stops the ticker (background
 *   flushes piggyback on batch deliveries instead — no alarms/WorkManager).
 * - [onFlushDue] is injected by DI (the uploader lives in homebase-core, which
 *   this module cannot reference). It must be cheap and rate-gated by the callee.
 */
class LocationTrackingCoordinator(
    private val preferences: LocationPreferences,
    private val tracker: LocationTracker,
    private val scope: CoroutineScope,
) {
    private val logger = Logger.withTag("LocationTrackingCoordinator")

    /** Wired in AppModule to LocationTrackUploaderService.flushIfDue(). */
    var onFlushDue: suspend () -> Unit = {}

    private var processStarted = false
    private var isForeground = false
    private var tickerJob: Job? = null

    fun onProcessStart() {
        if (processStarted || !tracker.isAvailable) return
        processStarted = true

        if (preferences.trackingEnabled.value) {
            tracker.start(TrackingMode.Background)
            logger.i { "Process start: tracking re-armed" }
        }

        observeAppForeground { foreground ->
            isForeground = foreground
            if (preferences.trackingEnabled.value) {
                tracker.setMode(if (foreground) TrackingMode.Foreground else TrackingMode.Background)
            }
            if (foreground) startTicker() else stopTicker()
        }
    }

    suspend fun setTrackingEnabled(enabled: Boolean) {
        preferences.setTrackingEnabled(enabled)
        if (!tracker.isAvailable) return
        if (enabled) {
            tracker.start(if (isForeground) TrackingMode.Foreground else TrackingMode.Background)
            if (isForeground) startTicker()
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
     * LocationPreferences.reset() before this runs; if the wipe turned the
     * switch off, stop capturing.
     */
    fun reset() {
        if (!tracker.isAvailable) return
        if (!preferences.trackingEnabled.value) {
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
