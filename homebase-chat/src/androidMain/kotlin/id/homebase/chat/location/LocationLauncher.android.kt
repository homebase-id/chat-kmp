package id.homebase.chat.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import co.touchlab.kermit.Logger
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "LocationLauncher.android"

/**
 * Cap on the active "wait for a single live update" request when no cached fix is available.
 * Without a cap, indoor / no-signal devices spin until the OS gives up (often >30s).
 */
private const val FETCH_TIMEOUT_MS = 15_000L

@Composable
actual fun rememberCurrentLocationLauncher(
    onResult: (LocationResult) -> Unit,
): LocationLauncher {
    val context = LocalContext.current
    val onResultState = rememberUpdatedState(onResult)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        Logger.d(tag = TAG) { "permission result granted=$granted (raw=$result)" }
        if (granted) {
            fetchLocation(context, onResultState.value)
        } else {
            onResultState.value(LocationResult.PermissionDenied)
        }
    }

    return remember(context) {
        object : LocationLauncher {
            override fun launch() {
                if (hasLocationPermission(context)) {
                    Logger.d(tag = TAG) { "launch: permission already granted, fetching" }
                    fetchLocation(context, onResultState.value)
                } else {
                    Logger.d(tag = TAG) { "launch: requesting permission" }
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                }
            }
        }
    }
}

private fun hasLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}

/**
 * Stock Android [LocationManager] approach (mirrors Signal's `LocationRetriever`). We tried
 * `FusedLocationProviderClient` first; it returned `null` for both `lastLocation` and
 * `getCurrentLocation` on real devices and emulators alike, even when a mock GPS fix had been
 * applied. LocationManager reads from the platform's location cache directly, has no Play
 * Services dependency, and handles the emulator's mock-provider correctly.
 *
 * Strategy:
 *  1. Try `getLastKnownLocation` on GPS_PROVIDER, then NETWORK_PROVIDER — cheap, no battery
 *     drain. On real devices this almost always returns a fix because Maps/Weather/etc. have
 *     populated the cache.
 *  2. If both are null, register a one-shot [LocationListener] via `requestLocationUpdates`
 *     and remove ourselves on the first callback (or after [FETCH_TIMEOUT_MS]).
 *
 * Permission is guarded by [hasLocationPermission] at every call site.
 */
@SuppressLint("MissingPermission")
private fun fetchLocation(
    context: Context,
    onResult: (LocationResult) -> Unit,
) {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val delivered = AtomicBoolean(false)
    val handler = Handler(Looper.getMainLooper())

    fun deliverOnce(result: LocationResult) {
        if (delivered.compareAndSet(false, true)) {
            handler.removeCallbacksAndMessages(null)
            onResult(result)
        }
    }

    fun deliverFix(location: Location, source: String) {
        Logger.d(tag = TAG) {
            "$source success lat=${location.latitude} lon=${location.longitude}"
        }
        deliverOnce(
            LocationResult.Success(
                LocationFix(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                )
            )
        )
    }

    val enabledProviders = buildList {
        if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            add(LocationManager.GPS_PROVIDER)
        }
        if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            add(LocationManager.NETWORK_PROVIDER)
        }
    }

    if (enabledProviders.isEmpty()) {
        Logger.w(tag = TAG) {
            "no location providers enabled (Location turned off in OS settings? airplane mode?)"
        }
        deliverOnce(LocationResult.Unavailable)
        return
    }
    Logger.d(tag = TAG) { "fetchLocation: enabled providers = $enabledProviders" }

    // 1. Try cached fixes from each enabled provider, freshest first.
    var bestCached: Location? = null
    for (provider in enabledProviders) {
        val cached = locationManager.getLastKnownLocation(provider)
        if (cached != null && (bestCached == null || cached.time > bestCached.time)) {
            bestCached = cached
        }
    }
    if (bestCached != null) {
        deliverFix(bestCached, "lastKnownLocation(${bestCached.provider})")
        return
    }

    // 2. No cached fix — request a one-shot live update from the best available provider
    //    (GPS preferred, NETWORK fallback), with a hard timeout.
    val activeProvider = enabledProviders.first()
    Logger.d(tag = TAG) {
        "no cached fix; requesting single update from $activeProvider (${FETCH_TIMEOUT_MS}ms cap)"
    }

    val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            locationManager.removeUpdates(this)
            deliverFix(location, "requestLocationUpdates($activeProvider)")
        }

        // Required for minSdk < 30 — defaulted on API 30+, but we still target 27.
        @Deprecated("Required for compat with API < 30; provider status is not used.")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    val timeoutRunnable = Runnable {
        Logger.w(tag = TAG) {
            "single update timed out after ${FETCH_TIMEOUT_MS}ms — removing listener"
        }
        locationManager.removeUpdates(listener)
        deliverOnce(LocationResult.Unavailable)
    }
    handler.postDelayed(timeoutRunnable, FETCH_TIMEOUT_MS)

    locationManager.requestLocationUpdates(
        activeProvider,
        /* minTimeMs = */ 0L,
        /* minDistanceM = */ 0f,
        listener,
        Looper.getMainLooper(),
    )
}
