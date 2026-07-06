package id.homebase.core.location.tracking

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Looper
import co.touchlab.kermit.Logger
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import id.homebase.api.ActivityProvider
import id.homebase.api.coroutines.supervisedScope
import kotlinx.coroutines.launch

actual fun createLocationTracker(sink: LocationPointSink): LocationTracker =
    AndroidLocationTracker(sink)

/**
 * FusedLocationProvider-based tracker, no foreground service.
 *
 * Two concurrent registrations while tracking:
 * - **PendingIntent** (always on): balanced power, 60s interval with up to 10min
 *   batched delivery — the OS coalesces wakeups; deliveries land in
 *   [LocationUpdatesReceiver] even when the app process is dead (the receiver
 *   wake restarts it). This is the only path that works in the background
 *   without a foreground service, at whatever cadence the OS allows.
 * - **Foreground overlay** (only for a foreground profile): a callback feeding
 *   [sink] directly for precise capture while the user has the app open. Its
 *   accuracy/cadence per profile comes from the common [TrackingProfile.spec]
 *   table (#846, #978). Removed on backgrounding; the
 *   PendingIntent stays registered throughout so there is no gap, and the
 *   store's time/displacement dedup absorbs the overlap.
 *
 * NOTE: this codebase previously saw fused *one-shot* reads (lastLocation /
 * getCurrentLocation) return null (see chat's LocationLauncher.android.kt);
 * continuous requestLocationUpdates is a different code path but must be
 * validated on a device before shipping — fallback is the framework
 * LocationManager with the same receiver.
 */
private class AndroidLocationTracker(
    private val sink: LocationPointSink,
) : LocationTracker {

    private val logger = Logger.withTag("AndroidLocationTracker")
    private val scope = supervisedScope("AndroidLocationTracker")

    override val isAvailable: Boolean = true

    private var started = false
    private var foregroundCallback: LocationCallback? = null
    // Which foreground profile the overlay is currently registered with (null = no overlay), so a
    // change between LiveForeground and HistoryForeground re-registers with the new params.
    private var foregroundProfile: TrackingProfile? = null

    private val context: Context get() = ActivityProvider.requireApplicationContext()
    private val fusedClient get() = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    override fun start(profile: TrackingProfile) {
        if (started) {
            setProfile(profile)
            return
        }
        runCatching {
            val request = LocationRequest.Builder(
                AndroidBackgroundBaseline.ACCURACY.toFusedPriority(),
                AndroidBackgroundBaseline.INTERVAL_MS,
            )
                .setMaxUpdateDelayMillis(AndroidBackgroundBaseline.MAX_DELAY_MS)
                .setMinUpdateDistanceMeters(AndroidBackgroundBaseline.MIN_DISPLACEMENT_M.toFloat())
                .build()
            fusedClient.requestLocationUpdates(request, backgroundPendingIntent())
            started = true
            logger.i { "Started background PendingIntent updates" }
        }.onFailure {
            // Typically SecurityException when permission was revoked behind our back.
            logger.e(it) { "Failed to start location updates" }
            return
        }
        setProfile(profile)
    }

    @SuppressLint("MissingPermission")
    override fun setProfile(profile: TrackingProfile) {
        if (!started) return
        val overlay = foregroundOverlayParams(profile)
        if (overlay == null) {
            // Background profile — drop the precise overlay; the PendingIntent keeps running.
            removeForegroundOverlay()
            return
        }
        if (foregroundProfile == profile) return // overlay already running with the right params
        // Foreground profile changed (e.g. History→Live when a share starts) — re-register.
        removeForegroundOverlay()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                val points = result.locations.map { it.toRawPoint(fg = true) }
                if (points.isEmpty()) return
                scope.launch { sink.submit(points) }
            }
        }
        runCatching {
            val request = LocationRequest.Builder(overlay.priority, overlay.intervalMs)
                .setMinUpdateDistanceMeters(overlay.minDisplacementM)
                .build()
            fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            foregroundCallback = callback
            foregroundProfile = profile
            logger.i { "Foreground overlay on (profile=$profile)" }
        }.onFailure { logger.e(it) { "Failed to start foreground overlay" } }
    }

    override fun stop() {
        removeForegroundOverlay()
        fusedClient.removeLocationUpdates(backgroundPendingIntent())
        started = false
        logger.i { "Stopped all location updates" }
    }

    private fun removeForegroundOverlay() {
        foregroundCallback?.let {
            fusedClient.removeLocationUpdates(it)
            foregroundCallback = null
            foregroundProfile = null
            logger.i { "Foreground overlay off" }
        }
    }

    private data class OverlayParams(val priority: Int, val intervalMs: Long, val minDisplacementM: Float)

    /** [TrackingProfile.spec] translated to Fused units; null for background profiles (no precise overlay). */
    private fun foregroundOverlayParams(profile: TrackingProfile): OverlayParams? {
        if (!profile.isForeground) return null
        val spec = profile.spec
        return OverlayParams(
            priority = spec.accuracy.toFusedPriority(),
            intervalMs = checkNotNull(spec.minIntervalMs) { "foreground spec must set minIntervalMs" },
            minDisplacementM = spec.minDisplacementM.toFloat(),
        )
    }

    private fun TrackingAccuracy.toFusedPriority(): Int = when (this) {
        TrackingAccuracy.Precise -> Priority.PRIORITY_HIGH_ACCURACY
        TrackingAccuracy.Balanced -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
        // Never registered on Android: background profiles run the baseline only (no overlay).
        TrackingAccuracy.Coarse -> error("no Fused registration uses Coarse")
    }

    private fun backgroundPendingIntent(): PendingIntent {
        val intent = Intent(context, LocationUpdatesReceiver::class.java)
            .setAction(LocationUpdatesReceiver.ACTION_LOCATION_UPDATE)
        // FLAG_MUTABLE is required: the fused provider appends the location
        // payload to the intent on delivery.
        return PendingIntent.getBroadcast(
            context,
            PENDING_INTENT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private companion object {
        const val PENDING_INTENT_REQUEST_CODE = 5610
    }
}

internal fun android.location.Location.toRawPoint(fg: Boolean): RawLocationPoint = RawLocationPoint(
    t = time,
    lat = latitude,
    lon = longitude,
    acc = if (hasAccuracy()) accuracy.toDouble() else null,
    alt = if (hasAltitude()) altitude else null,
    altAcc = if (hasVerticalAccuracy()) verticalAccuracyMeters.toDouble() else null,
    spd = if (hasSpeed()) speed.toDouble() else null,
    hdg = if (hasBearing()) bearing.toDouble() else null,
    src = LocationSources.FUSED,
    fg = fg,
)
