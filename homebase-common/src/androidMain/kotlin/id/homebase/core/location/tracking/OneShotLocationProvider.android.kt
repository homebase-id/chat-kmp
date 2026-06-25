package id.homebase.core.location.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import co.touchlab.kermit.Logger
import id.homebase.api.ActivityProvider
import id.homebase.core.permissions.isLocationPermissionGranted
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "OneShotLocation.android"

actual fun createOneShotLocationProvider(): OneShotLocationProvider = AndroidOneShotLocationProvider

/**
 * Stock Android [LocationManager] one-shot (mirrors the former chat `LocationLauncher.android`):
 * try the cached fix from each enabled provider (cheap, no battery drain), else register a one-shot
 * [LocationListener] with a hard timeout. FusedLocationProviderClient returned null for both
 * lastLocation/getCurrentLocation on real devices + emulators, so LocationManager is used directly.
 */
private object AndroidOneShotLocationProvider : OneShotLocationProvider {

    @SuppressLint("MissingPermission")
    override suspend fun getCurrentFix(timeoutMs: Long): GpsFixResult {
        if (!isLocationPermissionGranted()) return GpsFixResult.PermissionDenied
        val context = ActivityProvider.requireApplicationContext()
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        val enabledProviders = buildList {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        if (enabledProviders.isEmpty()) {
            Logger.w(tag = TAG) { "no providers enabled (location off / airplane mode?)" }
            return GpsFixResult.Unavailable
        }

        // 1. Freshest cached fix across enabled providers.
        var bestCached: Location? = null
        for (provider in enabledProviders) {
            val cached = lm.getLastKnownLocation(provider)
            if (cached != null && (bestCached == null || cached.time > bestCached.time)) bestCached = cached
        }
        if (bestCached != null) {
            Logger.d(tag = TAG) { "cached fix from ${bestCached.provider}" }
            return GpsFixResult.Success(bestCached.toRaw())
        }

        // 2. No cached fix — one-shot live update from the best provider, capped by timeoutMs.
        val activeProvider = enabledProviders.first()
        Logger.d(tag = TAG) { "no cached fix; one-shot from $activeProvider (cap ${timeoutMs}ms)" }
        val fix = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }

                    @Deprecated("Required for API < 30 compat; status unused.")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
                lm.requestLocationUpdates(activeProvider, 0L, 0f, listener, Looper.getMainLooper())
            }
        }
        return if (fix != null) GpsFixResult.Success(fix.toRaw()) else GpsFixResult.Timeout
    }

    private fun Location.toRaw(): RawLocationPoint = RawLocationPoint(
        t = time,
        lat = latitude,
        lon = longitude,
        acc = if (hasAccuracy()) accuracy.toDouble() else null,
        alt = if (hasAltitude()) altitude else null,
        spd = if (hasSpeed()) speed.toDouble() else null,
        hdg = if (hasBearing()) bearing.toDouble() else null,
        src = provider ?: "gps",
        fg = true,
    )
}
