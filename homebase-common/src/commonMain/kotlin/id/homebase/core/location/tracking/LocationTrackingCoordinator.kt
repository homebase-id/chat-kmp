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
 *   Entering the foreground also fires [onForegroundEntry] — a one-shot "force a fix if stale" so a
 *   brief app-open records a point instead of waiting on the 30 s interval (#878 root cause A).
 * - [onFlushDue] / [liveShareActive] / [onForegroundEntry] are injected by DI (the uploader, the
 *   share service and LocationService live in higher modules / form a cycle this one cannot
 *   reference directly). They must be cheap.
 *
 * **Product expectation (#878).** Without a persistent foreground-service notification (intentionally
 * out of scope — it draws heavy Play/App Store review scrutiny), Android throttles background
 * location to a few fixes/hour regardless of speed. So a long backgrounded moving session (sailing,
 * screen off) is inherently sparse by design. What we *can* do without a notification: a fresh point
 * on every app-open and push-receipt (via [onForegroundEntry] / the push path → LocationService's
 * force-on-stale primitive) plus a good track while the app is open — all rate-limited by that
 * primitive's battery guard.
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

    /**
     * Wired in AppModule to LocationService.forceCaptureIfTracking(AppForeground) — the gated entry
     * for automatic triggers, NOT requestLatestGps directly. Fired once each time the app enters the
     * foreground while a persistent consumer wants GPS ([isCaptureWanted]), so a brief app-open forces
     * an immediate fix instead of waiting on the capture interval (#878). Must be cheap and
     * self-throttling — LocationService's primitive owns the staleness + battery guard.
     */
    var onForegroundEntry: suspend () -> Unit = {}

    /** Transient GPS demands (live map open, one-shot fix, …). See [acquireTransientDemand]. */
    private val demand = GpsDemand()

    private var processStarted = false

    /**
     * Whether the app is currently foregrounded, as last reported by [observeAppForeground]. Public
     * so the uploader can decide whether to defer background uploads in battery saver (#878 follow-up:
     * defer only while backgrounded; a foregrounded user always uploads). False until the first
     * foreground observation (a headless cold start is correctly treated as background).
     */
    var isForeground = false
        private set

    private var tickerJob: Job? = null

    // Tracks armed-state + the last-applied profile, so [applyGpsHold] logs only on transitions
    // (armed/stopped or a profile change), not on every idempotent re-evaluation.
    private var gpsRunning = false
    private var lastProfile: TrackingProfile? = null

    /** GPS should run when history is allowed, a live share needs it, OR a transient hold is held. */
    private fun wantsGps(): Boolean =
        demand.wants(preferences.allowLocationHistory.value, liveShareActive())

    /** GPS can only physically run with hardware present AND location permission granted. */
    private fun canRunGps(): Boolean = tracker.isAvailable && isLocationPermissionGranted()

    /**
     * Whether a *persistent* consumer (location history or an active live share) wants GPS and it can
     * physically run. This is the gate for the automatic force-on-stale captures (app-open, push): we
     * only spin up the radio on demand when something is actually recording/relaying. It deliberately
     * excludes pure transient demands (the live map already acquires its own fix), and is narrower
     * than [wantsGps], which also arms for transient holds.
     */
    fun isCaptureWanted(): Boolean =
        (preferences.allowLocationHistory.value || liveShareActive()) && canRunGps()

    /**
     * The [isCaptureWanted] inputs spelled out for the log, so a skipped push-capture line
     * says WHICH condition closed the gate (#988) instead of just "skipped". All four reads
     * are the same cheap, non-suspending calls [applyGpsHold] performs on every re-eval.
     */
    fun captureGateExplanation(): String =
        "allowHistory=${preferences.allowLocationHistory.value} liveShare=${liveShareActive()} " +
            "trackerAvailable=${tracker.isAvailable} permission=${isLocationPermissionGranted()}"

    /**
     * The currently-armed tracking profile's name, or null when GPS isn't running. Read by the #1109
     * background-transition log (via an injected lambda in AuthConnectionCoordinator) to attribute a
     * background window to its active location profile. Mirrors [lastProfile], which is null while the
     * radio is stopped.
     */
    fun currentProfileLabel(): String? = lastProfile?.name

    /** The acquisition profile for the current foreground state + consumers (#846). */
    private fun currentProfile(): TrackingProfile =
        demand.resolveProfile(isForeground, preferences.allowLocationHistory.value, liveShareActive())

    /**
     * Start or stop the tracker to match [wantsGps] (gated by [canRunGps]). The single start/stop
     * decision point — idempotent, [tracker] start/stop tolerate repeats.
     */
    private fun applyGpsHold() {
        if (!tracker.isAvailable) return
        val wants = wantsGps()
        if (wants && canRunGps()) {
            val profile = currentProfile()
            tracker.start(profile)
            if (isForeground) startTicker() else stopTicker()
            if (!gpsRunning || profile != lastProfile) {
                logger.i {
                    "GPS armed (profile=$profile " +
                        "allowHistory=${preferences.allowLocationHistory.value} " +
                        "liveShare=${liveShareActive()} transient=${demand.hasTransient()})"
                }
            }
            gpsRunning = true
            lastProfile = profile
        } else {
            tracker.stop()
            stopTicker()
            if (gpsRunning) {
                gpsRunning = false
                lastProfile = null
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

        // A foreground/background transition changes both the desired profile (live/history × FG/BG)
        // and the flush ticker; re-applying the hold handles start/stop/profile/ticker + logging.
        observeAppForeground { foreground ->
            val enteredForeground = foreground && !isForeground
            isForeground = foreground
            applyGpsHold()
            // Force a fresh fix on app-open when a persistent consumer is recording — a brief open
            // would otherwise close before the capture interval fires and record nothing (#878). The
            // primitive self-throttles (staleness + battery guard), so re-firing here is cheap.
            if (enteredForeground && isCaptureWanted()) {
                scope.launch { onForegroundEntry() }
            }
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

    /**
     * Foreground flush ticker (#846 Q2). Its job is to **drain the history buffer to hour files even
     * when no new fixes are arriving** — e.g. a stationary user whose fixes are all dropped by the
     * store's stationary-noise filter would otherwise never trigger a flush (those are fired from the
     * router's persist path). It is intentionally slower (120 s) than the uploader's own 60 s flush
     * rate-gate: the gate bounds how often a flush *can* run; this ticker only guarantees one happens
     * periodically while foregrounded. Background flushes piggyback on batch deliveries instead.
     */
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
