package id.homebase.chat.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "LocationLauncher.android"

/**
 * Fused Location can spin for 30+ seconds when no provider has a cached fix (emulator without
 * a mock location, indoors with no signal, etc.) before resolving with `null`. Cap the wait so
 * the user gets feedback quickly.
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

@SuppressLint("MissingPermission") // Guarded by hasLocationPermission() at every call site.
private fun fetchLocation(
    context: Context,
    onResult: (LocationResult) -> Unit,
) {
    val client = LocationServices.getFusedLocationProviderClient(context)
    val cts = CancellationTokenSource()
    val delivered = AtomicBoolean(false)
    val handler = Handler(Looper.getMainLooper())

    fun deliverOnce(result: LocationResult) {
        if (delivered.compareAndSet(false, true)) {
            handler.removeCallbacksAndMessages(null)
            onResult(result)
        }
    }

    fun deliverFix(location: android.location.Location, source: String) {
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

    fun activeFetch() {
        val timeoutRunnable = Runnable {
            Logger.w(tag = TAG) { "getCurrentLocation timed out after ${FETCH_TIMEOUT_MS}ms — cancelling" }
            cts.cancel()
            deliverOnce(LocationResult.Unavailable)
        }
        handler.postDelayed(timeoutRunnable, FETCH_TIMEOUT_MS)
        Logger.d(tag = TAG) { "active fetch: getCurrentLocation (BALANCED_POWER, ${FETCH_TIMEOUT_MS}ms cap)" }
        client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cts.token)
            .addOnSuccessListener { location ->
                if (location == null) {
                    Logger.w(tag = TAG) {
                        "getCurrentLocation returned null (no mock location? location services off?)"
                    }
                    deliverOnce(LocationResult.Unavailable)
                } else {
                    deliverFix(location, "getCurrentLocation")
                }
            }
            .addOnFailureListener { e ->
                Logger.w(throwable = e, tag = TAG) { "getCurrentLocation failed" }
                deliverOnce(LocationResult.Unavailable)
            }
    }

    // Try lastLocation first: it's the cached fix from any provider (including the emulator's
    // mock location, which getCurrentLocation often refuses to return because it wants a fresh
    // active fix). Cheap, no battery drain. Falls back to active fetch if nothing is cached.
    Logger.d(tag = TAG) { "fetchLocation: trying lastLocation first" }
    client.lastLocation
        .addOnSuccessListener { last ->
            if (last != null) {
                deliverFix(last, "lastLocation")
            } else {
                Logger.d(tag = TAG) { "lastLocation null → falling back to active fetch" }
                activeFetch()
            }
        }
        .addOnFailureListener { e ->
            Logger.w(throwable = e, tag = TAG) { "lastLocation failed → falling back to active fetch" }
            activeFetch()
        }
}
