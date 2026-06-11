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
 * - **Foreground overlay** (only in [TrackingMode.Foreground]): a high-accuracy
 *   callback at 15s/10m feeding [sink] directly for precise capture while the
 *   user has the app open. Removed on backgrounding; the PendingIntent stays
 *   registered throughout so there is no gap, and the store's time/displacement
 *   dedup absorbs the overlap.
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

    private val context: Context get() = ActivityProvider.requireApplicationContext()
    private val fusedClient get() = LocationServices.getFusedLocationProviderClient(context)

    @SuppressLint("MissingPermission")
    override fun start(mode: TrackingMode) {
        if (started) {
            setMode(mode)
            return
        }
        runCatching {
            val request = LocationRequest.Builder(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                BACKGROUND_INTERVAL_MS,
            )
                .setMaxUpdateDelayMillis(BACKGROUND_MAX_DELAY_MS)
                .setMinUpdateDistanceMeters(BACKGROUND_MIN_DISPLACEMENT_M)
                .build()
            fusedClient.requestLocationUpdates(request, backgroundPendingIntent())
            started = true
            logger.i { "Started background PendingIntent updates" }
        }.onFailure {
            // Typically SecurityException when permission was revoked behind our back.
            logger.e(it) { "Failed to start location updates" }
            return
        }
        setMode(mode)
    }

    @SuppressLint("MissingPermission")
    override fun setMode(mode: TrackingMode) {
        if (!started) return
        when (mode) {
            TrackingMode.Foreground -> {
                if (foregroundCallback != null) return
                val callback = object : LocationCallback() {
                    override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                        val points = result.locations.map { it.toRawPoint(fg = true) }
                        if (points.isEmpty()) return
                        scope.launch { sink.submit(points) }
                    }
                }
                runCatching {
                    val request = LocationRequest.Builder(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        FOREGROUND_INTERVAL_MS,
                    )
                        .setMinUpdateDistanceMeters(FOREGROUND_MIN_DISPLACEMENT_M)
                        .build()
                    fusedClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
                    foregroundCallback = callback
                    logger.i { "Foreground precise overlay on" }
                }.onFailure { logger.e(it) { "Failed to start foreground overlay" } }
            }

            TrackingMode.Background -> {
                foregroundCallback?.let {
                    fusedClient.removeLocationUpdates(it)
                    foregroundCallback = null
                    logger.i { "Foreground precise overlay off" }
                }
            }
        }
    }

    override fun stop() {
        foregroundCallback?.let { fusedClient.removeLocationUpdates(it) }
        foregroundCallback = null
        fusedClient.removeLocationUpdates(backgroundPendingIntent())
        started = false
        logger.i { "Stopped all location updates" }
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

        const val BACKGROUND_INTERVAL_MS = 60_000L
        const val BACKGROUND_MAX_DELAY_MS = 600_000L
        const val BACKGROUND_MIN_DISPLACEMENT_M = 25f

        const val FOREGROUND_INTERVAL_MS = 15_000L
        const val FOREGROUND_MIN_DISPLACEMENT_M = 10f
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
    src = "fused",
    fg = fg,
)
